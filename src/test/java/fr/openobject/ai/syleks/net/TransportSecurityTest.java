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
package fr.openobject.ai.syleks.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransportSecurityTest {
    @Test
    void exceptionsNormalizeOriginsAndNeverAuthorizeAnotherHostPortOrOAuth() {
        var policy =
                TransportSecurity.Policy.parse(
                        List.of("http://REVIEWED.example/", "http://reviewed.example:8000"));
        assertEquals(
                java.util.Set.of("http://reviewed.example:80", "http://reviewed.example:8000"),
                policy.insecureHttpOrigins());
        var allowed = URI.create("http://reviewed.example:80/v1/chat/completions");
        assertEquals(allowed, policy.requireAllowed(allowed));
        assertTrue(policy.warning(allowed).contains("INSECURE HTTP: http://reviewed.example:80"));
        for (String url :
                List.of(
                        "http://reviewed.example:8001/v1",
                        "http://other.example/v1",
                        "http://reviewed.example.evil/v1",
                        "http://reviewed.example?token=fake-key"))
            assertThrows(
                    IllegalArgumentException.class, () -> policy.requireAllowed(URI.create(url)));
        assertThrows(
                IllegalArgumentException.class, () -> TransportSecurity.requireSecure(allowed));
        assertThrows(
                IllegalArgumentException.class,
                () -> TransportSecurity.Policy.parse(List.of()).requireAllowed(allowed));
        assertEquals("", policy.warning(URI.create("https://reviewed.example/v1")));
    }

    @Test
    void rejectsBroadOrMalformedExceptionsWithoutDisclosingInput() {
        for (Object value :
                List.of(
                        "*",
                        List.of("*"),
                        List.of("http://*.example"),
                        List.of("https://example.com"),
                        List.of("http://example.com/path"),
                        List.of("http://user:fake-secret@example.com"),
                        List.of("http://example.com?fake-secret"),
                        List.of("http://example.com:0"),
                        List.of("http://example.com:65536"),
                        List.of(42))) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> TransportSecurity.Policy.parse(value));
            assertFalse(error.getMessage().contains("fake-secret"));
        }
    }

    @Test
    void permitsTlsAndOnlyUnambiguousLoopbackHttp() {
        for (String url :
                List.of(
                        "https://models.example/v1",
                        "https://10.0.0.1:8443/mcp",
                        "http://localhost:8000",
                        "http://LOCALHOST:8000",
                        "http://127.0.0.1",
                        "http://127.255.255.254:65535",
                        "http://[::1]:8080",
                        "http://[0:0:0:0:0:0:0:1]")) {
            assertEquals(URI.create(url), TransportSecurity.requireSecure(URI.create(url)), url);
        }
    }

    @Test
    void rejectsRemoteHttpAmbiguousAuthoritiesAndUrlSecrets() {
        for (String url :
                List.of(
                        "http://models.example",
                        "http://10.0.0.1",
                        "http://localhost.evil",
                        "http://evil.localhost",
                        "http://localhost.",
                        "http://127.1",
                        "http://2130706433",
                        "http://127.00.0.1",
                        "http://[::ffff:127.0.0.1]",
                        "http://[::ffff:7f00:1]",
                        "http://[::2]",
                        "http://[::1%25lo]",
                        "https://host:0",
                        "https://host:65536",
                        "https://host:",
                        "https://host:-1",
                        "https://user:fake-key@host",
                        "https://host?token=fake-key",
                        "https://host/#fake-key",
                        "file:///tmp/config",
                        "//host")) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> TransportSecurity.requireSecure(URI.create(url)),
                            url);
            assertFalse(error.getMessage().contains("fake-key"));
        }
    }

    @Test
    void normalizesOriginsWithoutPathsOrSecretValues() {
        assertEquals(
                "https://example.com:443",
                TransportSecurity.origin(URI.create("https://EXAMPLE.com/path")));
        assertEquals("http://[::1]:80", TransportSecurity.origin(URI.create("http://[::1]/")));
    }
}
