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

import fr.openobject.ai.syleks.provider.ProviderException;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

@DefaultScoped
public class TokenRefreshService {
    private final CredentialStore store;
    private final OAuthHttp http;
    private final URI endpoint;

    public TokenRefreshService(CredentialStore store, OAuthHttp http) {
        this(store, http, URI.create("https://auth.openai.com/api/accounts/oauth/token"));
    }

    TokenRefreshService(CredentialStore store, OAuthHttp http, URI endpoint) {
        this.store = store;
        this.http = http;
        this.endpoint = endpoint;
    }

    public Credentials usable(String accountId) throws Exception {
        if (accountId == null)
            throw new IOException("Select an authenticated account with /accounts or /connect");
        return store.locked(
                accountId,
                () -> {
                    // Re-read after acquiring both JVM and OS locks: another CLI may have rotated
                    // tokens.
                    var current = store.load(accountId);
                    if (store.refreshPending(accountId))
                        throw new IOException(
                                "Previous refresh has an uncertain outcome; reconnect this saved account to avoid reusing a rotated token. Credentials retained.");
                    if (!current.connected())
                        throw new IOException("Account signed out; reconnect with /accounts");
                    if (!current.account().providerId().equals("chatgpt")) return current;
                    if (!current.planAuthorized()) return current;
                    Instant now = Instant.now();
                    if (now.isBefore(current.expiresAt().minusSeconds(60))) return current;
                    if (now.isBefore(current.earliestRefreshAt())) {
                        if (now.isBefore(current.expiresAt())) return current;
                        throw new IOException(
                                "Token expired before earliest_refresh_at; retry later");
                    }
                    if (current.refreshToken() == null)
                        throw new IOException("No renewable session; reconnect");
                    Map<?, ?> tokens = null;
                    store.startRefresh(accountId);
                    // Never retry an ambiguous network failure after submitting a rotating refresh
                    // token.
                    // Only explicit temporary HTTP rejection is safe to retry with this token.
                    for (int retry = 0; retry < 3; retry++) {
                        try {
                            tokens =
                                    http.post(
                                            endpoint,
                                            Map.of(
                                                    "grant_type",
                                                    "refresh_token",
                                                    "client_id",
                                                    current.account().clientId(),
                                                    "refresh_token",
                                                    current.refreshToken(),
                                                    "resource",
                                                    ChatGptOAuthService.RESOURCE));
                            break;
                        } catch (ProviderException e) {
                            store.clearRefresh(accountId);
                            if (Set.of(
                                            "invalid_grant",
                                            "invalid_refresh_token",
                                            "token_expired",
                                            "refresh_token_expired",
                                            "refresh_token_invalidated",
                                            "refresh_token_reused")
                                    .contains(e.code())) store.save(current.signedOut());
                            if (e.status() < 500 || retry == 2) throw e;
                            Thread.sleep(200L * (retry + 1));
                            store.startRefresh(accountId);
                        } catch (IOException e) {
                            throw new IOException(
                                    "Token refresh transport failed; credentials retained. Retry later or reconnect.");
                        }
                    }
                    var updated =
                            ChatGptOAuthService.credentials(current.account(), tokens, current);
                    // Persist the whole rotated token set before returning it to any caller.
                    store.save(updated);
                    return updated;
                });
    }
}
