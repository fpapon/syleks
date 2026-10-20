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

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@DefaultScoped
public class ChatGptOAuthService {
    public static final String RESOURCE = "https://api.openai.com/v1";
    public static final String SCOPES =
            "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private final CredentialStore store;
    private final OAuthHttp http;
    private final IdTokenValidator validator;
    private final URI authorize;
    private final URI token;
    private final Duration timeout;

    public ChatGptOAuthService(CredentialStore store, OAuthHttp http, IdTokenValidator validator) {
        this(
                store,
                http,
                validator,
                URI.create("https://auth.openai.com/api/accounts/authorize"),
                URI.create("https://auth.openai.com/api/accounts/oauth/token"),
                Duration.ofSeconds(
                        Long.parseLong(
                                System.getenv()
                                        .getOrDefault("SYLEKS_AUTH_TIMEOUT_SECONDS", "180"))));
    }

    ChatGptOAuthService(
            CredentialStore store,
            OAuthHttp http,
            IdTokenValidator validator,
            URI authorize,
            URI token,
            Duration timeout) {
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("OAuth timeout must be positive");
        this.store = store;
        this.http = http;
        this.validator = validator;
        this.authorize = authorize;
        this.token = token;
        this.timeout = timeout;
    }

    public static String random() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String challenge(String verifier) throws NoSuchAlgorithmException {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256")
                                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    public Attempt begin(String accountId) throws Exception {
        return new Attempt(accountId == null ? null : store.load(accountId), null);
    }

    public static final class AuthorizationCodeExpiredException extends IOException {
        AuthorizationCodeExpiredException() {
            super("Authorization code expired; restart authorization with the issued client ID");
        }
    }

    public final class Attempt implements AutoCloseable {
        private final String state = random(), nonce = random(), verifier = random();
        private final Credentials previous;
        private final String host;
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final CompletableFuture<Credentials> result = new CompletableFuture<>();
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final URI redirect;
        private final URI url;
        private boolean cancelled;
        private final String requestedClient;
        private volatile String issuedClient;
        private volatile boolean retryableCode;

        private Attempt(Credentials previous, String retryClient) throws Exception {
            this.previous = previous;
            if (previous != null && !previous.account().providerId().equals("chatgpt"))
                throw new IllegalArgumentException("Select a ChatGPT account");
            host = store.hostId();
            requestedClient =
                    previous == null
                            ? retryClient == null ? "dynamic_agent_client" : retryClient
                            : previous.account().clientId();
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            redirect =
                    URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback");
            var params = new LinkedHashMap<String, String>();
            params.put("client_id", requestedClient);
            if (requestedClient.equals("dynamic_agent_client"))
                params.put("agent_name_hint", "Syleks");
            params.put("ext_agent_host_id", host);
            params.put("response_type", "code");
            params.put("redirect_uri", redirect.toString());
            params.put("scope", SCOPES);
            params.put("resource", RESOURCE);
            params.put("state", state);
            params.put("nonce", nonce);
            params.put("code_challenge_method", "S256");
            params.put("code_challenge", challenge(verifier));
            if (previous != null) {
                if (previous.idToken() != null) params.put("id_token_hint", previous.idToken());
                if (previous.account().email() != null)
                    params.put("login_hint", previous.account().email());
                if (!previous.planAuthorized()) params.put("prompt", "consent");
            }
            url = URI.create(authorize + "?" + OAuthHttp.form(params));
            server.createContext(
                    "/",
                    exchange -> {
                        String page = "Authentication failed. Return to Syleks and try again.";
                        int status = 400;
                        Credentials accepted = null;
                        Exception failure = null;
                        boolean handling = false;
                        try {
                            if (!exchange.getRequestMethod().equals("GET")
                                    || !exchange.getRequestURI()
                                            .getPath()
                                            .equals("/auth/callback")) {
                                status = 404;
                            } else {
                                var query = query(exchange.getRequestURI().getRawQuery());
                                if (!MessageDigest.isEqual(
                                        state.getBytes(StandardCharsets.UTF_8),
                                        query.getOrDefault("state", "")
                                                .getBytes(StandardCharsets.UTF_8)))
                                    throw new IOException("Invalid OAuth state");
                                if (claimed.compareAndSet(false, true)) {
                                    handling = true;
                                    if (query.containsKey("error"))
                                        throw new IOException(
                                                "Authorization denied. You can retry /connect.");
                                    String client = query.get("client_id");
                                    if (!requestedClient.equals("dynamic_agent_client")) {
                                        if (client != null && !client.equals(requestedClient))
                                            throw new IOException("Unexpected OAuth client ID");
                                        client = requestedClient;
                                    }
                                    if (client == null
                                            || client.equals("dynamic_agent_client")
                                            || !client.matches("[A-Za-z0-9_-]{1,200}"))
                                        throw new IOException(
                                                "Incomplete dynamic client registration");
                                    issuedClient = client;
                                    String code = query.get("code");
                                    if (code == null || code.isBlank())
                                        throw new IOException("Malformed OAuth callback");
                                    var tokens =
                                            http.post(
                                                    token,
                                                    Map.of(
                                                            "grant_type",
                                                            "authorization_code",
                                                            "client_id",
                                                            client,
                                                            "code",
                                                            code,
                                                            "code_verifier",
                                                            verifier,
                                                            "redirect_uri",
                                                            redirect.toString(),
                                                            "resource",
                                                            RESOURCE));
                                    String idToken = CredentialStore.str(tokens, "id_token");
                                    if (idToken == null) throw new IOException("ID token missing");
                                    var identity = validator.validate(idToken, client, nonce);
                                    if (previous != null
                                            && (!identity.subject()
                                                            .equals(previous.account().subject())
                                                    || !identity.issuer()
                                                            .equals(previous.account().issuer())))
                                        throw new IOException(
                                                "Authenticated identity differs from the selected account");
                                    var account =
                                            new ProviderAccount(
                                                    "chatgpt",
                                                    previous == null
                                                            ? UUID.randomUUID().toString()
                                                            : previous.account().id(),
                                                    identity.email(),
                                                    identity.issuer(),
                                                    identity.subject(),
                                                    client,
                                                    host);
                                    var credentials = credentials(account, tokens, null);
                                    synchronized (this) {
                                        if (cancelled || result.isDone())
                                            throw new CancellationException();
                                        store.locked(
                                                account.id(),
                                                () -> {
                                                    store.save(credentials);
                                                    return null;
                                                });
                                        accepted = credentials;
                                    }
                                    status = 200;
                                    page =
                                            "Authentication successful.<br>You can return to Syleks.";
                                }
                            }
                        } catch (Exception e) {
                            if (e
                                            instanceof
                                            fr.openobject.ai.syleks.provider.ProviderException
                                                    providerError
                                    && providerError.code().equals("invalid_grant")) {
                                retryableCode = true;
                                e = new AuthorizationCodeExpiredException();
                            }
                            if (handling)
                                failure =
                                        e instanceof IOException
                                                ? e
                                                : new IOException(
                                                        "Authentication failed; retry /connect");
                        }
                        byte[] html =
                                ("<!doctype html><html><head><meta charset=\"utf-8\"><title>Syleks</title></head><body><p>"
                                                + page
                                                + "</p></body></html>")
                                        .getBytes(StandardCharsets.UTF_8);
                        try {
                            exchange.getResponseHeaders()
                                    .set("Content-Type", "text/html; charset=utf-8");
                            exchange.getResponseHeaders().set("Cache-Control", "no-store");
                            exchange.getResponseHeaders()
                                    .set("Content-Security-Policy", "default-src 'none'");
                            exchange.sendResponseHeaders(status, html.length);
                            exchange.getResponseBody().write(html);
                        } catch (IOException ignored) {
                            // A browser closing its tab must not roll back already validated
                            // credentials.
                        } finally {
                            exchange.close();
                            if (accepted != null) result.complete(accepted);
                            else if (failure != null) result.completeExceptionally(failure);
                        }
                    });
            server.setExecutor(executor);
            server.start();
        }

        public Attempt restart() throws Exception {
            if (!retryableCode || issuedClient == null)
                throw new IllegalStateException("OAuth attempt cannot be retried");
            close();
            return new Attempt(previous, issuedClient);
        }

        public URI authorizationUri() {
            return url;
        }

        /** Manual URL deliberately omits retained identity hints so it is safe to display ephemerally. */
        public URI manualUri() {
            var params = query(url.getRawQuery());
            params.remove("id_token_hint");
            params.remove("login_hint");
            return URI.create(authorize + "?" + OAuthHttp.form(params));
        }

        public URI redirectUri() {
            return redirect;
        }

        public Credentials await() throws Exception {
            try {
                return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw new IOException("Authentication failed");
            } catch (TimeoutException e) {
                throw new IOException("Authentication timed out; retry /connect");
            } finally {
                close();
            }
        }

        @Override
        public synchronized void close() {
            cancelled = true;
            result.cancel(true);
            server.stop(0);
            executor.shutdownNow();
        }

        @Override
        public String toString() {
            return "ChatGPT OAuth attempt[redacted]";
        }
    }

    public static LinkedHashMap<String, String> query(String query) {
        var values = new LinkedHashMap<String, String>();
        if (query == null || query.length() > 32768)
            throw new IllegalArgumentException("Malformed callback");
        for (var part : query.split("&")) {
            var entry = part.split("=", 2);
            if (entry.length != 2
                    || values.put(
                                    URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                                    URLDecoder.decode(entry[1], StandardCharsets.UTF_8))
                            != null) throw new IllegalArgumentException("Malformed callback");
        }
        return values;
    }

    static Credentials credentials(ProviderAccount account, Map<?, ?> tokens, Credentials old)
            throws IOException {
        var now = Instant.now();
        String access = CredentialStore.str(tokens, "access_token");
        String granted = CredentialStore.str(tokens, "scope");
        if (old == null
                && (access == null || access.isBlank())
                && (granted == null
                        || !Arrays.asList(granted.split("\\s+"))
                                .contains("chatgpt.tokens.use.direct")))
            return new Credentials(
                    account,
                    granted == null ? Set.of() : Set.copyOf(Arrays.asList(granted.split("\\s+"))),
                    null,
                    null,
                    CredentialStore.str(tokens, "id_token"),
                    now,
                    Instant.EPOCH,
                    now);
        String type = CredentialStore.str(tokens, "token_type");
        if (type != null && !type.equalsIgnoreCase("Bearer"))
            throw new IOException("Unsupported OAuth token type");
        if (access == null
                || access.isBlank()
                || !(tokens.get("expires_in") instanceof Number expires)
                || expires.longValue() <= 0)
            throw new IOException("Incomplete OAuth token response");
        String refresh = CredentialStore.str(tokens, "refresh_token");
        if (old != null && (refresh == null || refresh.isBlank()))
            throw new IOException("Refresh response lacks replacement refresh token; reconnect");
        String scope = CredentialStore.str(tokens, "scope");
        Set<String> scopes =
                scope == null
                        ? old == null ? Set.of() : old.scopes()
                        : Set.copyOf(Arrays.asList(scope.strip().split("\\s+")));
        Instant earliest = now;
        if (tokens.get("earliest_refresh_at") instanceof Number n)
            earliest = Instant.ofEpochSecond(n.longValue());
        else if (tokens.get("earliest_refresh_at") instanceof String s) {
            try {
                earliest =
                        s.matches("[0-9]+")
                                ? Instant.ofEpochSecond(Long.parseLong(s))
                                : Instant.parse(s);
            } catch (RuntimeException e) {
                throw new IOException("Invalid refresh timing");
            }
        }
        String id = CredentialStore.str(tokens, "id_token");
        return new Credentials(
                account,
                scopes,
                access,
                refresh,
                old != null ? old.idToken() : id,
                now,
                now.plusSeconds(expires.longValue()),
                earliest);
    }
}
