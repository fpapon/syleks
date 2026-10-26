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
package fr.openobject.ai.syleks.app;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.provider.OllamaLlmProvider;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InsecureTransportConfigTest {
    @TempDir Path root;

    private Path settings() {
        return root.resolve("home/.config/syleks/agent-config.json");
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void deliversOnlyToReviewedOriginAndRevokesOnConfigurationReload() throws Exception {
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<String>();
        var credentials = new CopyOnWriteArrayList<String>();
        proxy.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        requests.add(exchange.getRequestURI().toString());
                        credentials.add(
                                exchange.getRequestHeaders().getFirst("Authorization") == null
                                        ? ""
                                        : exchange.getRequestHeaders().getFirst("Authorization"));
                        exchange.getRequestBody().readAllBytes();
                        String response =
                                exchange.getRequestURI().getPath().endsWith("tags")
                                        ? "{\"models\":[{\"name\":\"fixture\"}]}"
                                        : exchange.getRequestURI()
                                                        .getPath()
                                                        .endsWith("chat/completions")
                                                ? "data: [DONE]\n\n"
                                                : "{\"message\":{\"content\":\"ok\"},\"done\":true}\n";
                        var bytes = response.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        proxy.start();
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            String global =
                    """
                {"model":"reviewed/fixture", "insecure_http_origins":["http://REVIEWED.invalid:8000/"],
                 "provider": {
                   "reviewed":{"options":{"baseURL":"http://reviewed.invalid:8000/v1","apiKey":"fake-key",
                      "proxy":"http://127.0.0.1:%d"},"models":{"fixture":{}}},
                   "ollama":{"options":{"baseURL":"http://reviewed.invalid:8000","proxy":"http://127.0.0.1:%d"}}
                 }}
                """
                            .formatted(proxy.getAddress().getPort(), proxy.getAddress().getPort());
            write(settings(), global);
            var config = AppConfigFixtures.defaults(mapper.instance(), root);
            var client = new OllamaClient(config, mapper.instance());
            client.stream("reviewed/fixture", List.of(), ignored -> {});
            var ollama = new OllamaLlmProvider(client, config, mapper.instance());
            assertEquals("fixture", ollama.listModels("").getFirst().id());
            client.stream("ollama/fixture", List.of(), ignored -> {});
            assertEquals(List.of("Bearer fake-key", "", ""), credentials);
            assertEquals(3, requests.size());
            assertTrue(
                    requests.stream()
                            .allMatch(url -> url.startsWith("http://reviewed.invalid:8000/")));
            var session = new ChatSession(client, config);
            assertTrue(
                    session.snapshot().messages().stream()
                            .anyMatch(
                                    message ->
                                            message.content()
                                                    .contains(
                                                            "INSECURE HTTP: http://reviewed.invalid:8000")));
            assertTrue(
                    session.history().stream()
                            .noneMatch(message -> message.content().contains("INSECURE HTTP:")),
                    "Transport notices must stay out of model history and conversation exports");
            write(
                    settings(),
                    global.replace(
                            "http://REVIEWED.invalid:8000/", "http://reviewed.invalid:8001"));
            assertThrows(
                    IllegalStateException.class,
                    () -> AppConfigFixtures.defaults(mapper.instance(), root));
            write(settings(), global.replace("[\"http://REVIEWED.invalid:8000/\"]", "[]"));
            assertThrows(
                    IllegalStateException.class,
                    () -> AppConfigFixtures.defaults(mapper.instance(), root));
            assertEquals(
                    3, requests.size(), "Denied/revoked configurations must not send any request");
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void trustedProjectsAndEnvironmentCannotGrantExceptionsButCanUseGlobalOnes() throws Exception {
        Path project = root.resolve("project");
        write(
                project.resolve(".syleks/agent-config.json"),
                """
            {"insecure_http_origins":["http://reviewed.example:8000"],
             "mcp":{"fixture":{"type":"http","url":"http://reviewed.example:8000/mcp"}}}
            """);
        var trust = new ProjectTrust(root.resolve("home"), project);
        trust.grant(trust.digest());
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            AppConfigFixtures.atWorkspace(
                                    mapper.instance(),
                                    root,
                                    project,
                                    Map.of(
                                            "SYLEKS_INSECURE_HTTP_ORIGINS",
                                            "http://reviewed.example:8000")));
            write(settings(), "{\"insecure_http_origins\":[\"http://reviewed.example:8000\"]}");
            var allowed = AppConfigFixtures.atWorkspace(mapper.instance(), root, project);
            assertTrue(allowed.mcpServers().containsKey("fixture"));
            assertEquals(1, allowed.transport().warnings().size());
            write(settings(), "{}");
            assertThrows(
                    IllegalStateException.class,
                    () -> AppConfigFixtures.atWorkspace(mapper.instance(), root, project));
        }
    }
}
