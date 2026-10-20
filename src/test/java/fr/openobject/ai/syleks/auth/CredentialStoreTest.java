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

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.provider.ProviderException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialStoreTest {
    @TempDir Path root;

    @Test
    void persistsWithPrivatePermissionsAtomicReplacementAndStableHostAcrossRestarts()
            throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var restarted = new CredentialStore(f.json, f.store.root());
            assertEquals(c, restarted.load(c.account().id()));
            assertEquals(f.store.hostId(), restarted.hostId());
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                for (var dir :
                        List.of(
                                f.store.root(),
                                f.store.root().resolve("auth"),
                                f.store.root().resolve("auth/accounts")))
                    assertEquals(
                            PosixFilePermissions.fromString("rwx------"),
                            Files.getPosixFilePermissions(dir));
                assertEquals(
                        PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(
                                f.store
                                        .root()
                                        .resolve("auth/accounts/" + c.account().id() + ".json")));
            }
            try (var files = Files.walk(f.store.root())) {
                assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")));
            }
            assertFalse(c.toString().contains(c.accessToken()));
            assertFalse(c.toString().contains(c.refreshToken()));
        }
    }

    @Test
    void refusesCredentialSymlinksAndDynamicIds() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            Path file = f.store.root().resolve("auth/accounts/" + c.account().id() + ".json");
            Path external = root.resolve("external");
            Files.writeString(external, "do not change");
            Files.delete(file);
            Files.createSymbolicLink(file, external);
            assertThrows(Exception.class, () -> f.store.save(c));
            assertThrows(Exception.class, () -> f.store.load(c.account().id()));
            assertEquals("do not change", Files.readString(external));
            var a = c.account();
            var dynamic =
                    new ProviderAccount(
                            a.providerId(),
                            a.id(),
                            a.email(),
                            a.issuer(),
                            a.subject(),
                            "dynamic_agent_client",
                            a.hostId());
            assertThrows(
                    Exception.class,
                    () ->
                            f.store.save(
                                    new Credentials(
                                            dynamic,
                                            c.scopes(),
                                            c.accessToken(),
                                            c.refreshToken(),
                                            c.idToken(),
                                            c.receivedAt(),
                                            c.expiresAt(),
                                            c.earliestRefreshAt())));
        }
    }

    @Test
    void serializesConcurrentRefreshAndCommitsTheRotatedSetAtomically() throws Exception {
        try (var f = new AuthFixture(root);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var c = f.login(null);
            f.expired(c);
            var first = pool.submit(() -> f.refresh.usable(c.account().id()));
            var second = pool.submit(() -> f.refresh.usable(c.account().id()));
            assertEquals("rotated-refresh", first.get(5, TimeUnit.SECONDS).refreshToken());
            assertEquals(first.get(), second.get());
            assertEquals(1, f.refreshes.get());
            assertFalse(f.form.get().containsKey("scope"));
            assertEquals(c.account().clientId(), f.form.get().get("client_id"));
            assertEquals("test-refresh", f.form.get().get("refresh_token"));
            var loaded = new CredentialStore(f.json, f.store.root()).load(c.account().id());
            assertEquals("new-access", loaded.accessToken());
            assertEquals("rotated-refresh", loaded.refreshToken());
        }
    }

    @Test
    void coordinatesRotationAcrossSeparateJvmProcesses() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            f.expired(c);
            var one = process(f, c.account().id());
            var two = process(f, c.account().id());
            assertTrue(one.waitFor(15, TimeUnit.SECONDS));
            assertTrue(two.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, one.exitValue(), new String(one.getErrorStream().readAllBytes()));
            assertEquals(0, two.exitValue(), new String(two.getErrorStream().readAllBytes()));
            assertEquals(1, f.refreshes.get());
            assertEquals("rotated-refresh", f.store.load(c.account().id()).refreshToken());
        }
    }

    private Process process(AuthFixture f, String account) throws Exception {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        AuthProcess.class.getName(),
                        f.store.root().toString(),
                        f.base.resolve("token").toString(),
                        account)
                .start();
    }

    @Test
    void honorsEarliestRefreshAndRetainsCredentialsOnTemporaryFailuresButClearsRevokedTokens()
            throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            f.store.save(
                    new Credentials(
                            c.account(),
                            c.scopes(),
                            c.accessToken(),
                            c.refreshToken(),
                            c.idToken(),
                            c.receivedAt(),
                            Instant.now().plusSeconds(20),
                            Instant.now().plusSeconds(10)));
            assertEquals(c.accessToken(), f.refresh.usable(c.account().id()).accessToken());
            assertEquals(0, f.refreshes.get());
            f.expired(c);
            var hits = new java.util.concurrent.atomic.AtomicInteger();
            f.tokenHandler.set(
                    ex -> {
                        hits.incrementAndGet();
                        AuthFixture.respond(ex, 503, "{\"error\":\"temporarily_unavailable\"}");
                    });
            assertThrows(Exception.class, () -> f.refresh.usable(c.account().id()));
            assertEquals(3, hits.get());
            assertEquals(c.refreshToken(), f.store.load(c.account().id()).refreshToken());
            f.tokenHandler.set(
                    ex ->
                            AuthFixture.respond(
                                    ex,
                                    400,
                                    "{\"error\":\"invalid_grant\",\"error_description\":\"test-refresh\"}"));
            var error =
                    assertThrows(ProviderException.class, () -> f.refresh.usable(c.account().id()));
            assertFalse(error.getMessage().contains(c.refreshToken()));
            assertFalse(f.store.load(c.account().id()).connected());
            assertEquals(
                    c.account().clientId(), f.store.load(c.account().id()).account().clientId());
        }
    }

    @Test
    void apiKeyLogoutOnlyClearsTheSelectedAccountAndKeepsRegistrationMetadata() throws Exception {
        try (var f = new AuthFixture(root)) {
            var first = f.auth.apiKey("fake-api-key-one");
            var second = f.auth.apiKey("fake-api-key-two");
            assertTrue(f.auth.logout(first.account().id()));
            assertFalse(f.store.load(first.account().id()).connected());
            assertTrue(f.store.load(second.account().id()).connected());
            assertEquals(2, f.auth.accounts().size());
        }
    }

    @Test
    void prefersVaultSecretsAndAtomicallyReplacesReferencesWithoutWritingTokensToDisk()
            throws Exception {
        try (var f = new AuthFixture(root)) {
            var secrets = new HashMap<String, String>();
            CredentialVault vault =
                    new CredentialVault() {
                        public void put(String reference, String secret) {
                            secrets.put(reference, secret);
                        }

                        public String get(String reference) {
                            return secrets.get(reference);
                        }

                        public void delete(String reference) {
                            secrets.remove(reference);
                        }
                    };
            var store = new CredentialStore(f.json, root.resolve("vault"), vault);
            var c = f.auth.apiKey("fake-sensitive-api-key");
            store.save(c);
            Path file = store.root().resolve("auth/accounts/" + c.account().id() + ".json");
            assertFalse(Files.readString(file).contains(c.accessToken()));
            assertTrue(Files.readString(file).contains("vault_ref"));
            assertEquals(c, store.load(c.account().id()));
            assertEquals(1, secrets.size());
            store.save(c);
            assertEquals(1, secrets.size());
            assertEquals(c, store.load(c.account().id()));
            store.save(c.signedOut());
            assertTrue(secrets.isEmpty());
            assertFalse(store.load(c.account().id()).connected());
        }
    }

    @Test
    void uncertainRefreshNeverReusesAPossiblyRotatedTokenAndReauthorizationRecovers()
            throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            f.expired(c);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            f.tokenHandler.set(
                    ex -> {
                        calls.incrementAndGet();
                        ex.close();
                    });
            assertThrows(Exception.class, () -> f.refresh.usable(c.account().id()));
            int attempted = calls.get();
            assertThrows(Exception.class, () -> f.refresh.usable(c.account().id()));
            assertEquals(attempted, calls.get());
            assertTrue(f.store.load(c.account().id()).connected());
            f.tokenHandler.set(null);
            assertTrue(f.login(c.account().id()).connected());
            assertFalse(f.store.refreshPending(c.account().id()));
        }
    }

    @Test
    void revokesTheRenewableSessionBeforeClearingOnlyThatAccountsTokens() throws Exception {
        try (var f = new AuthFixture(root)) {
            var first = f.login(null);
            var second = f.login(null);
            var revoked = new java.util.concurrent.atomic.AtomicBoolean();
            var http =
                    new OAuthHttp(f.json) {
                        public Map<?, ?> get(java.net.URI uri) {
                            return Map.of(
                                    "issuer",
                                    "https://auth.openai.com",
                                    "revocation_endpoint",
                                    "https://auth.openai.com/revoke");
                        }

                        public Map<?, ?> post(java.net.URI uri, Map<String, String> fields) {
                            assertTrue(f.store.refreshPending(first.account().id()) == false);
                            assertEquals(first.refreshToken(), fields.get("token"));
                            assertEquals(first.account().clientId(), fields.get("client_id"));
                            assertEquals("refresh_token", fields.get("token_type_hint"));
                            revoked.set(true);
                            return Map.of();
                        }
                    };
            assertTrue(
                    new AuthenticationService(f.store, f.oauth, f.refresh, http)
                            .logout(first.account().id()));
            assertTrue(revoked.get());
            assertFalse(f.store.load(first.account().id()).connected());
            assertTrue(f.store.load(second.account().id()).connected());
            assertEquals(
                    first.account().clientId(),
                    f.store.load(first.account().id()).account().clientId());
        }
    }
}
