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
package fr.openobject.ai.syleks.engineering;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.ai.ToolHandler;
import fr.openobject.ai.syleks.app.AppConfigFixtures;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineeringTransportTest {
    @TempDir Path root;

    @Test
    void permitsOnlyTheReviewedLocalFixtureAndBlocksLaterToolOutputAfterPolicyChanges()
            throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/chat",
                exchange -> {
                    received.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] body =
                            "{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"function\":{\"name\":\"read\",\"arguments\":{\"path\":\"fixture.txt\"}}}]},\"done\":true}\n"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            Path registry = EngineeringFixtures.registry(root, null, false);
            Path settings = root.resolve("home/.config/syleks/agent-config.json");
            Files.writeString(
                    settings,
                    EngineeringFixtures.JSON.write(
                            Map.of(
                                    "permission",
                                    Map.of("read", "allow", "bash", "deny", "task", "deny"),
                                    "provider",
                                    Map.of("ollama", Map.of("options", Map.of("baseURL", base))))));
            try (var container = ConfiguringContainer.of().start();
                    var mapper = container.lookup(JsonMapper.class)) {
                var unreviewed =
                        new OllamaClient(
                                AppConfigFixtures.defaults(mapper.instance(), root),
                                mapper.instance());
                assertThrows(
                        IOException.class,
                        () ->
                                unreviewed.streamPrepared(
                                        "ollama/test",
                                        "build",
                                        List.of(
                                                Map.of(
                                                        "role",
                                                        "user",
                                                        "content",
                                                        "synthetic private prompt")),
                                        text -> {},
                                        ToolHandler.NONE,
                                        null));
                assertTrue(received.isEmpty());
                unreviewed.close();
                EngineeringFixtures.registry(root, base + "/api/chat", false);
                var client =
                        new OllamaClient(
                                AppConfigFixtures.defaults(mapper.instance(), root),
                                mapper.instance());
                try {
                    var error =
                            assertThrows(
                                    IOException.class,
                                    () ->
                                            client.streamPrepared(
                                                    "ollama/test",
                                                    "build",
                                                    List.of(
                                                            Map.of(
                                                                    "role",
                                                                    "user",
                                                                    "content",
                                                                    "synthetic private prompt")),
                                                    text -> {},
                                                    call -> {
                                                        Files.writeString(registry, "{}");
                                                        return "private tool-output sentinel";
                                                    },
                                                    null));
                    assertTrue(error.getMessage().contains("CONFIGURATION_CHANGED"));
                    assertEquals(1, received.size());
                    assertTrue(received.getFirst().contains("synthetic private prompt"));
                    assertFalse(received.getFirst().contains("private tool-output sentinel"));
                } finally {
                    client.close();
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
