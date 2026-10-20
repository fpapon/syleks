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

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import fr.openobject.ai.syleks.app.*;
import io.yupiik.fusion.framework.api.*;
import io.yupiik.fusion.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local signed OIDC server. No real account, browser, subscription or network endpoint is used. */
public final class AuthFixture implements AutoCloseable {
    public final RuntimeContainer container = ConfiguringContainer.of().start();
    public final Instance<JsonMapper> mapper = container.lookup(JsonMapper.class);
    public final JsonMapper json = mapper.instance();
    public final AppConfig config;
    public final CredentialStore store;
    public final HttpServer server;
    public final URI base;
    public final RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
    public final IdTokenValidator validator;
    public final OAuthHttp http;
    public final ChatGptOAuthService oauth;
    public final TokenRefreshService refresh;
    public final AuthenticationService auth;
    public final AtomicReference<Map<String, String>> form = new AtomicReference<>();
    public final AtomicReference<String> nonce = new AtomicReference<>("nonce");
    public final AtomicReference<String> subject = new AtomicReference<>("test-subject");
    public final AtomicReference<String> scopes = new AtomicReference<>(ChatGptOAuthService.SCOPES);
    public final AtomicInteger refreshes = new AtomicInteger();
    public final AtomicReference<HttpHandler> tokenHandler = new AtomicReference<>();
    public final AtomicReference<HttpHandler> modelsHandler = new AtomicReference<>();
    public final AtomicReference<HttpHandler> responsesHandler = new AtomicReference<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AuthFixture(Path root) throws Exception {
        config = AppConfigFixtures.defaults(json, root);
        store = new CredentialStore(json, root.resolve("config/syleks"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        server.createContext(
                "/jwks", ex -> respond(ex, 200, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext(
                "/token",
                ex -> {
                    form.set(
                            ChatGptOAuthService.query(
                                    new String(
                                            ex.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8)));
                    if (tokenHandler.get() != null) {
                        tokenHandler.get().handle(ex);
                        return;
                    }
                    try {
                        boolean renewing = "refresh_token".equals(form.get().get("grant_type"));
                        if (renewing) refreshes.incrementAndGet();
                        respond(
                                ex,
                                200,
                                json.toString(
                                        Map.of(
                                                "token_type",
                                                "Bearer",
                                                "access_token",
                                                renewing ? "new-access" : "test-access",
                                                "refresh_token",
                                                renewing ? "rotated-refresh" : "test-refresh",
                                                "expires_in",
                                                3600,
                                                "earliest_refresh_at",
                                                Instant.now().minusSeconds(10).getEpochSecond(),
                                                "scope",
                                                scopes.get(),
                                                "id_token",
                                                signed(
                                                        form.get().get("client_id"),
                                                        nonce.get(),
                                                        subject.get(),
                                                        Instant.now().plusSeconds(3600)))));
                    } catch (Exception e) {
                        respond(ex, 500, "{}");
                    }
                });
        server.createContext(
                "/v1/models",
                ex -> {
                    if (modelsHandler.get() != null) modelsHandler.get().handle(ex);
                    else
                        respond(
                                ex,
                                200,
                                "{\"models\":[{\"slug\":\"test-model\",\"display_name\":\"Test model\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hide\"}]}");
                });
        server.createContext(
                "/v1/responses",
                ex -> {
                    if (responsesHandler.get() != null) responsesHandler.get().handle(ex);
                    else
                        respond(
                                ex,
                                200,
                                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\ndata: {\"type\":\"response.completed\"}\n\n");
                });
        server.setExecutor(executor);
        server.start();
        validator = new IdTokenValidator(base.resolve("jwks"), "https://auth.openai.com");
        http = new OAuthHttp(json);
        oauth =
                new ChatGptOAuthService(
                        store,
                        http,
                        validator,
                        base.resolve("authorize"),
                        base.resolve("token"),
                        Duration.ofSeconds(5));
        refresh = new TokenRefreshService(store, http, base.resolve("token"));
        auth = new AuthenticationService(store, oauth, refresh, http);
    }

    public String signed(String audience, String nonce, String sub, Instant expiry)
            throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer("https://auth.openai.com")
                        .audience(audience)
                        .subject(sub)
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(expiry))
                        .claim("nonce", nonce)
                        .claim("email", "user@example.test")
                        .claim("email_verified", true)
                        .build();
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    public Credentials login(String savedId) throws Exception {
        try (var attempt = oauth.begin(savedId)) {
            var params = ChatGptOAuthService.query(attempt.authorizationUri().getRawQuery());
            nonce.set(params.get("nonce"));
            var result =
                    callback(
                            attempt,
                            Map.of(
                                    "state",
                                    params.get("state"),
                                    "code",
                                    "test-code",
                                    "client_id",
                                    "oaiapp_test"));
            if (result.statusCode() != 200)
                throw new AssertionError("Callback failed: " + result.statusCode());
            return attempt.await();
        }
    }

    public HttpResponse<String> callback(
            ChatGptOAuthService.Attempt attempt, Map<String, String> values) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                attempt.redirectUri()
                                                        + "?"
                                                        + OAuthHttp.form(values)))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    public Credentials expired(Credentials c) throws Exception {
        var expired =
                new Credentials(
                        c.account(),
                        c.scopes(),
                        c.accessToken(),
                        c.refreshToken(),
                        c.idToken(),
                        Instant.now().minusSeconds(4000),
                        Instant.now().minusSeconds(10),
                        Instant.EPOCH);
        store.save(expired);
        return expired;
    }

    public static void respond(HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        validator.close();
        mapper.close();
        container.close();
    }
}
