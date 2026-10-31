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
package fr.openobject.ai.syleks.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import fr.openobject.ai.syleks.auth.*;
import fr.openobject.ai.syleks.net.TransportSecurity;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpSecretTransportTest {
    @TempDir Path root;

    private McpServerConfig parse(Map<String, Object> config) {
        return McpServerConfig.parse(Map.of("fixture", config), root).get("fixture");
    }

    private McpConnection connect(McpServerConfig config, ConfigSecrets secrets)
            throws IOException {
        return new McpConnection(
                config,
                root,
                Map.of("HOST_SECRET", "synthetic-host-secret"),
                root,
                new TransportSecurity.Policy(Set.of()),
                secrets);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void httpResolvesHeadersOnlyForBoundServerAndSuppressesServerErrorEchoes(boolean encrypted)
            throws Exception {
        String token = "synthetic-header-secret";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        var received = new AtomicReference<String>();
        server.createContext(
                "/mcp",
                exchange -> {
                    try (exchange) {
                        requests.incrementAndGet();
                        received.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
                        var data =
                                ("{\"error\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(400, data.length);
                        exchange.getResponseBody().write(data);
                    }
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            var definition = new LinkedHashMap<String, Object>(Map.of("type", "http", "url", url));
            var secrets =
                    encrypted
                            ? SecretFixtures.encrypted(root.resolve("secrets"))
                            : SecretFixtures.files(root.resolve("secrets"));
            String scope = ConfigSecrets.mcpScope(parse(definition), "headers", "X-Api-Key");
            String ref = encrypted ? secrets.encrypt(scope, token) : secrets.create(scope, token);
            definition.put("headers", Map.of("X-Api-Key", ref));
            try (var connection = connect(parse(definition), secrets)) {
                var error = assertThrows(IOException.class, connection::initialize);
                assertFalse(error.toString().contains(token));
                assertNull(error.getCause());
            }
            assertEquals(token, received.get());
            definition.put("url", url + "/other");
            assertThrows(IOException.class, () -> connect(parse(definition), secrets));
            assertEquals(1, requests.get());
            definition.put("url", url);
            definition.put("headers", Map.of("Authorization", ref));
            assertThrows(IOException.class, () -> connect(parse(definition), secrets));
            assertEquals(1, requests.get());
            assertFalse(parse(definition).toString().contains(token));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stdioSecretReachesOnlyConfiguredChildAndChangedCommandCannotReuseIt(boolean encrypted)
            throws Exception {
        Path marker = root.resolve("secret-result");
        Path inherited = root.resolve("host-result");
        var command =
                List.of(
                        "/bin/sh",
                        "-c",
                        "printf '%s' \"$APP_TOKEN\" > \"$1\"; printf '%s' \"${HOST_SECRET-unset}\" > \"$2\"",
                        "fixture",
                        marker.toString(),
                        inherited.toString());
        var definition =
                new LinkedHashMap<String, Object>(Map.of("type", "stdio", "command", command));
        var secrets =
                encrypted
                        ? SecretFixtures.encrypted(root.resolve("secrets"))
                        : SecretFixtures.files(root.resolve("secrets"));
        String token = "synthetic-stdio-secret";
        String scope = ConfigSecrets.mcpScope(parse(definition), "environment", "APP_TOKEN", root);
        String ref = encrypted ? secrets.encrypt(scope, token) : secrets.create(scope, token);
        definition.put("environment", Map.of("APP_TOKEN", ref));
        try (var connection = connect(parse(definition), secrets)) {
            assertThrows(
                    Exception.class,
                    connection::initialize); // fixture writes then exits, without a JSON-RPC
            // handshake
        }
        assertEquals(token, Files.readString(marker));
        assertEquals("unset", Files.readString(inherited));
        var baseline =
                fr.openobject.ai.syleks.app.SubprocessEnvironment.baseline(
                        Map.of("HOST_SECRET", token, "SYLEKS_CONFIG_KEY", token), root);
        var child = new ProcessBuilder("/usr/bin/env");
        child.environment().clear();
        child.environment().putAll(baseline);
        var process = child.start();
        assertFalse(
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .contains(token));
        assertEquals(0, process.waitFor());
        Path other = Files.createDirectory(root.resolve("other-workspace"));
        assertThrows(
                IOException.class,
                () ->
                        new McpConnection(
                                parse(definition),
                                other,
                                Map.of(),
                                root,
                                new TransportSecurity.Policy(Set.of()),
                                secrets));
        definition.put("command", List.of("/bin/sh", "-c", "touch forbidden"));
        assertThrows(IOException.class, () -> connect(parse(definition), secrets));
        assertFalse(Files.exists(root.resolve("forbidden")));
    }
}
