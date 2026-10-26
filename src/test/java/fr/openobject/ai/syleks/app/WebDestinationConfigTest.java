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
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebDestinationConfigTest {
    @TempDir Path root;

    private void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void onlyGlobalUserGrantsAuthorizePrimaryAndChildToolsAndReloadRevokesThem() throws Exception {
        var hits = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        hits.incrementAndGet();
                        var bytes = "fixture".getBytes();
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        server.start();
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            Path project = root.resolve("project"), home = root.resolve("home");
            Path settings = home.resolve(".config/syleks/agent-config.json");
            String grant = "\"web_private_origins\":[\"" + origin + "\"]";
            write(
                    project.resolve(".syleks/agent-config.json"),
                    "{"
                            + grant
                            + ",\"agent\":{\"reviewer\":{\"mode\":\"subagent\",\"permission\":\"allow\"}}}");
            write(
                    project.resolve(".syleks/agents/reviewer.md"),
                    "---\nmode: subagent\npermission: allow\n---\nReview the workspace.\n");
            var trust = new ProjectTrust(home, project);
            trust.grant(trust.digest());
            write(settings, "{\"permission\":\"allow\"}");
            var config =
                    AppConfigFixtures.atWorkspace(
                            mapper.instance(),
                            root,
                            project,
                            Map.of("SYLEKS_WEB_PRIVATE_ORIGINS", origin));
            assertTrue(config.webDestinations().privateOrigins().isEmpty());
            var call = new ToolCall("1", "webfetch", Map.of("url", origin));
            var denied = new ToolExecutor(config);
            for (String actor : List.of("build", "reviewer")) {
                var result = denied.execute(actor, call, (a, b, c) -> true, () -> {});
                assertEquals(ToolResult.Status.DENIED, result.status(), result::content);
            }
            assertEquals(0, hits.get());
            write(settings, "{\"permission\":\"allow\"," + grant + "}");
            config = AppConfigFixtures.atWorkspace(mapper.instance(), root, project);
            var allowed = new ToolExecutor(config);
            for (String actor : List.of("build", "reviewer"))
                assertEquals(
                        ToolResult.Status.SUCCESS,
                        allowed.execute(actor, call, (a, b, c) -> true, () -> {}).status());
            assertEquals(2, hits.get());
            write(settings, "{\"permission\":\"allow\"}");
            assertEquals(
                    ToolResult.Status.DENIED,
                    allowed.execute("build", call, (a, b, c) -> true, () -> {}).status(),
                    "Live policy revision invalidates the old executor");
            var revoked =
                    new ToolExecutor(
                            AppConfigFixtures.atWorkspace(mapper.instance(), root, project));
            assertEquals(
                    ToolResult.Status.DENIED,
                    revoked.execute("build", call, (a, b, c) -> true, () -> {}).status());
            assertEquals(2, hits.get());
        } finally {
            server.stop(0);
        }
    }
}
