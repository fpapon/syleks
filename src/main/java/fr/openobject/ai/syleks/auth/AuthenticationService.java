/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.auth;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@DefaultScoped
public class AuthenticationService {
    private final CredentialStore store;
    private final ChatGptOAuthService oauth;
    private final TokenRefreshService refresh;
    private final OAuthHttp http;

    public AuthenticationService(
            CredentialStore store,
            ChatGptOAuthService oauth,
            TokenRefreshService refresh,
            OAuthHttp http) {
        this.store = store;
        this.oauth = oauth;
        this.refresh = refresh;
        this.http = http;
    }

    public ChatGptOAuthService.Attempt login(String account) throws Exception {
        return oauth.begin(account);
    }

    /** Retry an expired code once, retaining the issued registration and generating fresh PKCE/state. */
    public Credentials authenticate(
            String account, java.util.function.Consumer<ChatGptOAuthService.Attempt> ready)
            throws Exception {
        var attempt = login(account);
        for (int retry = 0; ; retry++) {
            try (var current = attempt) {
                ready.accept(current);
                try {
                    return current.await();
                } catch (ChatGptOAuthService.AuthorizationCodeExpiredException e) {
                    if (retry >= 1) throw e;
                    attempt = current.restart();
                }
            }
        }
    }

    public List<Credentials> accounts() throws IOException {
        return store.accounts();
    }

    public Credentials credentials(String account) throws Exception {
        return refresh.usable(account);
    }

    public Credentials apiKey(String key) throws Exception {
        return apiKey("openai-api", key);
    }

    public Credentials apiKey(String providerId, String key) throws Exception {
        if (!fr.openobject.ai.syleks.app.AppConfig.apiKeyProvider(providerId))
            throw new IllegalArgumentException("Provider does not accept an API key");
        if (key == null || key.isBlank() || key.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Enter a valid API key");
        var account =
                new ProviderAccount(
                        providerId, UUID.randomUUID().toString(), null, null, null, null, null);
        var c =
                new Credentials(
                        account,
                        Set.of(),
                        key,
                        null,
                        null,
                        Instant.now(),
                        Instant.MAX,
                        Instant.EPOCH);
        store.locked(
                account.id(),
                () -> {
                    store.save(c);
                    return null;
                });
        return c;
    }

    /** Returns whether remote revocation was confirmed; local tokens are always removed. */
    public boolean logout(String accountId) throws Exception {
        return store.locked(
                accountId,
                () -> {
                    var c = store.load(accountId);
                    boolean revoked =
                            !c.account().providerId().equals("chatgpt") || c.refreshToken() == null;
                    try {
                        if (!revoked) {
                            var discovery =
                                    http.get(
                                            URI.create(
                                                    "https://auth.openai.com/.well-known/openid-configuration"));
                            if (!"https://auth.openai.com".equals(discovery.get("issuer")))
                                throw new IOException("Invalid OpenAI discovery issuer");
                            URI endpoint =
                                    URI.create(
                                            String.valueOf(discovery.get("revocation_endpoint")));
                            if (!endpoint.getScheme().equals("https")
                                    || !endpoint.getHost().equals("auth.openai.com")
                                    || endpoint.getUserInfo() != null)
                                throw new IOException("Invalid revocation endpoint");
                            for (int retry = 0; retry < 3; retry++) {
                                try {
                                    http.post(
                                            endpoint,
                                            Map.of(
                                                    "token",
                                                    c.refreshToken(),
                                                    "token_type_hint",
                                                    "refresh_token",
                                                    "client_id",
                                                    c.account().clientId()));
                                    revoked = true;
                                    break;
                                } catch (IOException e) {
                                    if (retry == 2) break;
                                    Thread.sleep(200L * (retry + 1));
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignored) {
                    } finally {
                        store.save(c.signedOut());
                    }
                    return revoked;
                });
    }
}
