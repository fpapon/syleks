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
package fr.openobject.ai.syleks.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import fr.openobject.ai.syleks.app.AppConfigFixtures;
import fr.openobject.ai.syleks.auth.*;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretTransportTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesOnlyAtRequestBoundaryAndRedactsHttpStreamingAndParserErrors(boolean encrypted)
            throws Exception {
        String token = "synthetic-transport-token";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        URI endpoint =
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()
                                + "/v1/chat/completions");
        var secrets =
                encrypted
                        ? SecretFixtures.encrypted(root.resolve("secrets"))
                        : SecretFixtures.files(root.resolve("secrets"));
        String scope = ConfigSecrets.providerScope("custom", endpoint);
        String ref = encrypted ? secrets.encrypt(scope, token) : secrets.create(scope, token);
        var requestToken = new AtomicReference<String>();
        var requestBody = new AtomicReference<String>();
        var reply = new AtomicReference<String>("{\"error\":{\"message\":\"" + token + "\"}}");
        var status = new AtomicInteger(400);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try (exchange) {
                        requestToken.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        requestBody.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status.get(), body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        server.start();
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            Path configFile = root.resolve("home/.config/syleks/agent-config.json");
            Files.createDirectories(configFile.getParent());
            Files.writeString(
                    configFile,
                    mapper.instance()
                            .toString(
                                    Map.of(
                                            "model",
                                            "custom/model",
                                            "provider",
                                            Map.of(
                                                    "custom",
                                                    Map.of(
                                                            "options",
                                                            Map.of(
                                                                    "baseURL",
                                                                    endpoint.resolve("/v1")
                                                                            .toString(),
                                                                    "apiKey",
                                                                    ref),
                                                            "models",
                                                            Map.of("model", Map.of()))))));
            var config = AppConfigFixtures.defaults(mapper.instance(), root);
            var connection = config.resolveModel("custom/model");
            assertEquals(ref, connection.apiKey());
            assertFalse(connection.toString().contains(token));
            var client = new OllamaClient(config, mapper.instance(), secrets);
            try {
                for (String response :
                        List.of(
                                reply.get(),
                                "data: {\"error\":{\"message\":\"" + token + "\"}}\n\n",
                                "data: { malformed " + token + "\n\n")) {
                    reply.set(response);
                    var error =
                            assertThrows(
                                    IOException.class,
                                    () -> client.stream("custom/model", List.of(), text -> {}));
                    assertFalse(error.toString().contains(token));
                    assertNull(error.getCause());
                    assertEquals("Bearer " + token, requestToken.get());
                    assertFalse(requestBody.get().contains(token));
                    assertFalse(requestBody.get().contains(ref));
                    status.set(200);
                }
                if (encrypted) return;
                secrets.revoke(ref);
                requestToken.set(null);
                assertThrows(
                        IOException.class,
                        () -> client.stream("custom/model", List.of(), text -> {}));
                assertNull(requestToken.get());
            } finally {
                client.close();
            }
        } finally {
            server.stop(0);
        }
    }
}
