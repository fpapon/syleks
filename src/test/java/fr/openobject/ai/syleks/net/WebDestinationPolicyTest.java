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

import java.io.IOException;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WebDestinationPolicyTest {
    private final WebDestinationPolicy publicOnly = new WebDestinationPolicy(Set.of());
    private final URI target = URI.create("https://service.example:8443/path?secret=synthetic");

    private InetAddress[] addresses(String... values) throws Exception {
        var result = new InetAddress[values.length];
        for (int i = 0; i < values.length; i++) result[i] = InetAddress.getByName(values[i]);
        return result;
    }

    @Test
    void distinguishesPublicPrivateAndUnconditionallyBlockedAddresses() throws Exception {
        var granted = new WebDestinationPolicy(Set.of("https://SERVICE.example.:8443/"));
        for (String ip :
                List.of("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111", "::ffff:8.8.8.8"))
            assertDoesNotThrow(() -> publicOnly.requireAllowed(target, addresses(ip)), ip);
        for (String ip :
                List.of(
                        "10.0.0.1",
                        "172.16.0.1",
                        "192.168.1.1",
                        "127.0.0.2",
                        "::1",
                        "fc00::1",
                        "fd12::1",
                        "::ffff:127.0.0.1",
                        "::ffff:10.0.0.1")) {
            assertThrows(
                    IOException.class, () -> publicOnly.requireAllowed(target, addresses(ip)), ip);
            assertDoesNotThrow(() -> granted.requireAllowed(target, addresses(ip)), ip);
        }
        for (String ip :
                List.of(
                        "0.0.0.0",
                        "0.1.2.3",
                        "100.100.100.200",
                        "169.254.169.254",
                        "169.254.170.2",
                        "224.0.0.1",
                        "255.255.255.255",
                        "192.0.0.1",
                        "192.0.2.1",
                        "198.18.0.1",
                        "198.51.100.1",
                        "203.0.113.1",
                        "::",
                        "fe80::1",
                        "ff02::1",
                        "::ffff:169.254.169.254",
                        "64:ff9b::a00:1",
                        "2002:7f00:1::",
                        "2001::1",
                        "2001:db8::1",
                        "3fff::1",
                        "fd00:ec2::254",
                        "168.63.129.16")) {
            assertThrows(
                    IOException.class, () -> granted.requireAllowed(target, addresses(ip)), ip);
        }
    }

    @Test
    void checksAllDnsAnswersAndExactSchemeHostAndPort() throws Exception {
        var grant = new WebDestinationPolicy(Set.of("https://service.example:8443"));
        assertThrows(
                IOException.class,
                () -> publicOnly.requireAllowed(target, addresses("8.8.8.8", "127.0.0.1")));
        assertThrows(
                IOException.class,
                () -> publicOnly.requireAllowed(target, addresses("127.0.0.1", "8.8.8.8")));
        assertThrows(
                IOException.class,
                () -> grant.requireAllowed(target, addresses("8.8.8.8", "169.254.169.254")));
        assertThrows(IOException.class, () -> grant.requireAllowed(target, addresses()));
        for (String origin :
                List.of(
                        "http://service.example:8443",
                        "https://service.example:8444",
                        "https://other.example:8443"))
            assertThrows(
                    IOException.class,
                    () -> grant.requireAllowed(URI.create(origin), addresses("127.0.0.1")));
        assertDoesNotThrow(() -> grant.requireAllowed(target, addresses("8.8.8.8", "127.0.0.1")));
    }

    @Test
    void normalizesNamesAndRejectsMalformedOrOverbroadGrantsWithoutEchoingSecrets() {
        assertEquals(
                "https://service.example/path?q=x",
                WebDestinationPolicy.normalize(URI.create("HTTPS://SERVICE.Example./path?q=x"))
                        .toString());
        assertEquals(
                WebDestinationPolicy.normalize(URI.create("http://[::1]")),
                WebDestinationPolicy.normalize(URI.create("http://[0:0:0:0:0:0:0:1]")));
        assertEquals(
                "http://127.0.0.1",
                WebDestinationPolicy.normalize(URI.create("http://[::ffff:127.0.0.1]")).toString());
        for (String value :
                List.of(
                        "*",
                        "https://*.example",
                        "http://user:fake-secret@localhost",
                        "http://localhost/path",
                        "http://localhost?fake-secret=x",
                        "http://localhost#fake-secret",
                        "http://localhost:0",
                        "http://localhost:65536",
                        "http://localhost:",
                        "http://[fe80::1%25eth0]")) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> WebDestinationPolicy.parse(List.of(value)));
            assertFalse(error.getMessage().contains("fake-secret"));
        }
        for (Object value :
                List.of(
                        "http://localhost",
                        Map.of(),
                        List.of(1),
                        Collections.nCopies(65, "http://localhost")))
            assertThrows(IllegalArgumentException.class, () -> WebDestinationPolicy.parse(value));
        assertThrows(IllegalArgumentException.class, () -> WebDestinationPolicy.parse(null));
    }
}
