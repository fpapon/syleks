package fr.openobject.ai.syleks.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OAuthTest {
    @TempDir Path root;

    @Test
    void dynamicallyRegistersValidatesAndReusesAnIssuedClientWithTheExactRedirect()
            throws Exception {
        try (var f = new AuthFixture(root);
                var attempt = f.oauth.begin(null)) {
            var query = ChatGptOAuthService.query(attempt.authorizationUri().getRawQuery());
            assertEquals("dynamic_agent_client", query.get("client_id"));
            assertEquals("Syleks", query.get("agent_name_hint"));
            assertEquals("S256", query.get("code_challenge_method"));
            assertEquals(ChatGptOAuthService.SCOPES, query.get("scope"));
            assertEquals(ChatGptOAuthService.RESOURCE, query.get("resource"));
            assertTrue(query.get("ext_agent_host_id").startsWith("urn:uuid:"));
            assertEquals("127.0.0.1", attempt.redirectUri().getHost());
            f.nonce.set(query.get("nonce"));
            var page =
                    f.callback(
                            attempt,
                            Map.of(
                                    "state",
                                    query.get("state"),
                                    "code",
                                    "not-a-real-code",
                                    "client_id",
                                    "oaiapp_test"));
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Authentication successful."));
            assertFalse(page.body().contains("test-access"));
            assertFalse(page.body().contains("not-a-real-code"));
            var c = attempt.await();
            assertTrue(c.planAuthorized());
            assertEquals("user@example.test", c.account().email());
            assertEquals("oaiapp_test", f.form.get().get("client_id"));
            assertEquals(attempt.redirectUri().toString(), f.form.get().get("redirect_uri"));
            assertEquals(
                    query.get("code_challenge"),
                    ChatGptOAuthService.challenge(f.form.get().get("code_verifier")));
            assertFalse(f.form.get().containsKey("client_secret"));
            try (var returning = f.oauth.begin(c.account().id())) {
                var next = ChatGptOAuthService.query(returning.authorizationUri().getRawQuery());
                assertEquals(c.account().clientId(), next.get("client_id"));
                assertEquals(c.account().hostId(), next.get("ext_agent_host_id"));
                assertEquals(c.idToken(), next.get("id_token_hint"));
                assertEquals(c.account().email(), next.get("login_hint"));
                assertNotEquals(query.get("state"), next.get("state"));
                assertNotEquals(query.get("nonce"), next.get("nonce"));
                assertFalse(returning.manualUri().toString().contains("id_token_hint"));
            }
        }
    }

    @Test
    void rejectsInvalidStateDuplicateParametersWrongPathAndMethodWithoutConsumingAttempt()
            throws Exception {
        try (var f = new AuthFixture(root);
                var attempt = f.oauth.begin(null)) {
            assertEquals(
                    400,
                    f.callback(attempt, Map.of("state", "wrong", "code", "code")).statusCode());
            var client = HttpClient.newHttpClient();
            assertEquals(
                    404,
                    client.send(
                                    HttpRequest.newBuilder(attempt.redirectUri().resolve("/other"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(
                    404,
                    client.send(
                                    HttpRequest.newBuilder(attempt.redirectUri())
                                            .POST(HttpRequest.BodyPublishers.noBody())
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(
                    400,
                    client.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            attempt.redirectUri()
                                                                    + "?state=a&state=b"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            var q = ChatGptOAuthService.query(attempt.authorizationUri().getRawQuery());
            f.nonce.set(q.get("nonce"));
            assertEquals(
                    200,
                    f.callback(
                                    attempt,
                                    Map.of(
                                            "state",
                                            q.get("state"),
                                            "code",
                                            "code",
                                            "client_id",
                                            "oaiapp_test"))
                            .statusCode());
            assertTrue(attempt.await().connected());
        }
    }

    @Test
    void refusesConsentFailureMissingClientAndUnexpectedReturningClient() throws Exception {
        try (var f = new AuthFixture(root)) {
            for (var extra :
                    List.of(Map.of("error", "access_denied"), Map.of("code", "missing-client"))) {
                try (var attempt = f.oauth.begin(null)) {
                    var values = new HashMap<>(extra);
                    values.put(
                            "state",
                            ChatGptOAuthService.query(attempt.authorizationUri().getRawQuery())
                                    .get("state"));
                    assertEquals(400, f.callback(attempt, values).statusCode());
                    assertThrows(Exception.class, attempt::await);
                }
            }
            assertTrue(f.store.accounts().isEmpty());
            var c = f.login(null);
            try (var attempt = f.oauth.begin(c.account().id())) {
                var values = ChatGptOAuthService.query(attempt.authorizationUri().getRawQuery());
                assertEquals(
                        400,
                        f.callback(
                                        attempt,
                                        Map.of(
                                                "state",
                                                values.get("state"),
                                                "code",
                                                "code",
                                                "client_id",
                                                "other-client"))
                                .statusCode());
                assertThrows(Exception.class, attempt::await);
            }
            assertEquals(c.accessToken(), f.store.load(c.account().id()).accessToken());
        }
    }

    @Test
    void validatesNonceSignatureIssuerAudienceExpiryAndSubjectBeforeSaving() throws Exception {
        try (var f = new AuthFixture(root)) {
            assertEquals(
                    "sub",
                    f.validator
                            .validate(
                                    f.signed(
                                            "client",
                                            "nonce",
                                            "sub",
                                            Instant.now().plusSeconds(60)),
                                    "client",
                                    "nonce")
                            .subject());
            assertThrows(
                    Exception.class,
                    () ->
                            f.validator.validate(
                                    f.signed(
                                            "client",
                                            "wrong",
                                            "sub",
                                            Instant.now().plusSeconds(60)),
                                    "client",
                                    "nonce"));
            assertThrows(
                    Exception.class,
                    () ->
                            f.validator.validate(
                                    f.signed(
                                            "other", "nonce", "sub", Instant.now().plusSeconds(60)),
                                    "client",
                                    "nonce"));
            assertThrows(
                    Exception.class,
                    () ->
                            f.validator.validate(
                                    f.signed(
                                            "client",
                                            "nonce",
                                            "sub",
                                            Instant.now().minusSeconds(120)),
                                    "client",
                                    "nonce"));
            assertThrows(
                    Exception.class,
                    () ->
                            f.validator.validate(
                                    f.signed("client", "nonce", "", Instant.now().plusSeconds(60)),
                                    "client",
                                    "nonce"));
            String token = f.signed("client", "nonce", "sub", Instant.now().plusSeconds(60));
            assertThrows(
                    Exception.class,
                    () ->
                            f.validator.validate(
                                    token.substring(0, token.lastIndexOf('.') + 1) + "invalid",
                                    "client",
                                    "nonce"));
            var c = f.login(null);
            f.subject.set("another-user");
            try (var attempt = f.oauth.begin(c.account().id())) {
                var q = ChatGptOAuthService.query(attempt.authorizationUri().getRawQuery());
                f.nonce.set(q.get("nonce"));
                assertEquals(
                        400,
                        f.callback(
                                        attempt,
                                        Map.of(
                                                "state",
                                                q.get("state"),
                                                "code",
                                                "code",
                                                "client_id",
                                                c.account().clientId()))
                                .statusCode());
                assertThrows(Exception.class, attempt::await);
            }
            assertEquals(c.account().subject(), f.store.load(c.account().id()).account().subject());
        }
    }

    @Test
    void retainsIdentityWithoutPlanPermissionAndSupportsIndependentRegistrationsWithTheSameEmail()
            throws Exception {
        try (var f = new AuthFixture(root)) {
            f.scopes.set("openid profile email");
            var first = f.login(null);
            var second = f.login(null);
            assertTrue(first.connected());
            assertFalse(first.planAuthorized());
            assertEquals(first.account().email(), second.account().email());
            assertNotEquals(first.account().id(), second.account().id());
            assertEquals(2, f.store.accounts().size());
            try (var attempt = f.oauth.begin(first.account().id())) {
                assertEquals(
                        "consent",
                        ChatGptOAuthService.query(attempt.authorizationUri().getRawQuery())
                                .get("prompt"));
            }
        }
    }

    @Test
    void timeoutAndCancellationCloseTheLoopbackListener() throws Exception {
        try (var f = new AuthFixture(root)) {
            var oauth =
                    new ChatGptOAuthService(
                            f.store,
                            f.http,
                            f.validator,
                            f.base.resolve("authorize"),
                            f.base.resolve("token"),
                            Duration.ofMillis(50));
            var attempt = oauth.begin(null);
            URI callback = attempt.redirectUri();
            assertTrue(
                    assertThrows(Exception.class, attempt::await)
                            .getMessage()
                            .contains("timed out"));
            assertThrows(
                    Exception.class,
                    () ->
                            HttpClient.newHttpClient()
                                    .send(
                                            HttpRequest.newBuilder(callback)
                                                    .timeout(Duration.ofSeconds(1))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.discarding()));
            var cancelled = f.oauth.begin(null);
            cancelled.close();
            assertThrows(CancellationException.class, cancelled::await);
            assertTrue(f.store.accounts().isEmpty());
        }
    }

    @Test
    void expiredAuthorizationCodeRestartsWithFreshPkceAndTheIssuedClient() throws Exception {
        try (var f = new AuthFixture(root)) {
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            var first = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
            f.tokenHandler.set(
                    ex -> {
                        f.tokenHandler.set(null);
                        AuthFixture.respond(ex, 400, "{\"error\":\"invalid_grant\"}");
                    });
            var c =
                    f.auth.authenticate(
                            null,
                            attempt -> {
                                try {
                                    var q =
                                            ChatGptOAuthService.query(
                                                    attempt.authorizationUri().getRawQuery());
                                    int number = attempts.incrementAndGet();
                                    if (number == 1) {
                                        first.set(q);
                                        assertEquals("dynamic_agent_client", q.get("client_id"));
                                    } else {
                                        assertEquals("oaiapp_test", q.get("client_id"));
                                        assertNotEquals(first.get().get("state"), q.get("state"));
                                        assertNotEquals(
                                                first.get().get("code_challenge"),
                                                q.get("code_challenge"));
                                    }
                                    f.nonce.set(q.get("nonce"));
                                    assertEquals(
                                            number == 1 ? 400 : 200,
                                            f.callback(
                                                            attempt,
                                                            Map.of(
                                                                    "state",
                                                                    q.get("state"),
                                                                    "code",
                                                                    "code-" + number,
                                                                    "client_id",
                                                                    "oaiapp_test"))
                                                    .statusCode());
                                } catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            });
            assertTrue(c.planAuthorized());
            assertEquals(2, attempts.get());
            assertEquals("oaiapp_test", c.account().clientId());
            assertEquals(1, f.auth.accounts().size());
        }
    }

    @Test
    void pkceMatchesTheRfc7636Vector() throws Exception {
        assertEquals(
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                ChatGptOAuthService.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertNotEquals(ChatGptOAuthService.random(), ChatGptOAuthService.random());
    }

    @Test
    void retainsAValidatedIdentityEvenWhenNoInferenceTokensWereGranted() throws Exception {
        try (var f = new AuthFixture(root)) {
            f.tokenHandler.set(
                    ex -> {
                        try {
                            AuthFixture.respond(
                                    ex,
                                    200,
                                    f.json.toString(
                                            Map.of(
                                                    "scope",
                                                    "openid profile email",
                                                    "id_token",
                                                    f.signed(
                                                            f.form.get().get("client_id"),
                                                            f.nonce.get(),
                                                            "subject",
                                                            Instant.now().plusSeconds(3600)))));
                        } catch (Exception e) {
                            throw new java.io.IOException(e);
                        }
                    });
            var c = f.login(null);
            assertTrue(c.connected());
            assertFalse(c.planAuthorized());
            assertNull(c.accessToken());
            assertEquals(c, f.store.load(c.account().id()));
            assertFalse(f.refresh.usable(c.account().id()).planAuthorized());
            assertEquals(0, f.refreshes.get());
        }
    }
}
