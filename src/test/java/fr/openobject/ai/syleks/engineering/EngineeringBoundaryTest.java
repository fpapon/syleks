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

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.app.AppConfigFixtures;
import fr.openobject.ai.syleks.mcp.McpClientManager;
import fr.openobject.ai.syleks.provider.*;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineeringBoundaryTest {
    @TempDir Path root;

    @Test
    void registryInspectionErrorsAreNotMisclassifiedAsOptionalAbsence() throws Exception {
        Files.createDirectories(root.resolve("home"));
        Files.writeString(root.resolve("home/.config"), "not a directory");
        assertThrows(
                IllegalStateException.class,
                () -> new EngineeringBoundary(root.resolve("home"), root));
    }

    @Test
    void requiresAnExactUserReviewedLocalRouteAndFailsClosedOnRegistryChanges() throws Exception {
        String endpoint = "http://127.0.0.1:11434/api/chat";
        Path file = EngineeringFixtures.registry(root, endpoint, false);
        var boundary = new EngineeringBoundary(root.resolve("home"), root.resolve("project"));
        assertTrue(boundary.active());
        assertDoesNotThrow(() -> boundary.checkInference("ollama", URI.create(endpoint), null));
        for (String target :
                List.of(
                        "http://localhost:11434/api/chat",
                        "http://127.0.0.1:11435/api/chat",
                        "https://remote.invalid/api/chat"))
            assertThrows(
                    IOException.class,
                    () -> boundary.checkInference("ollama", URI.create(target), null));
        assertThrows(
                IOException.class,
                () ->
                        boundary.checkInference(
                                "ollama",
                                URI.create(endpoint),
                                URI.create("http://127.0.0.1:3128")));
        assertThrows(
                IOException.class,
                () ->
                        boundary.checkInference(
                                "chatgpt",
                                URI.create("https://api.openai.com/v1/responses"),
                                null));
        Files.delete(file);
        assertThrows(
                IOException.class,
                () -> boundary.checkInference("ollama", URI.create(endpoint), null));
        assertThrows(IOException.class, () -> boundary.checkTool("read"));
    }

    @Test
    void cloudPermissionAndForgedReleaseMetadataCannotTurnAnApiKeyIntoASubscription()
            throws Exception {
        Path file = EngineeringFixtures.registry(root, null, true);
        var boundary = new EngineeringBoundary(root.resolve("home"), root.resolve("project"));
        assertTrue(
                assertThrows(
                                IOException.class,
                                () ->
                                        boundary.checkInference(
                                                "chatgpt",
                                                URI.create("https://api.openai.com/v1/responses"),
                                                null))
                        .getMessage()
                        .contains("AUTHORIZED_RELEASE_REQUIRED"));
        for (String id : List.of("openai-api", "google", "opencode", "spacexsi", "openrouter"))
            assertTrue(
                    assertThrows(
                                    IOException.class,
                                    () ->
                                            boundary.checkInference(
                                                    id,
                                                    URI.create("https://example.invalid"),
                                                    null))
                            .getMessage()
                            .contains("OFFICIAL_SUBSCRIPTION_REQUIRED"));
        var registry = EngineeringFixtures.JSON.readTree(Files.readString(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) registry)
                .put("release_id", "forged-approval");
        Files.writeString(file, registry.toString());
        assertThrows(
                IllegalStateException.class,
                () -> new EngineeringBoundary(root.resolve("home"), root.resolve("project")));
    }

    @Test
    void inheritedEnrollmentCannotBeRelaxedAndOtherProjectsKeepLegacyBehavior() throws Exception {
        EngineeringFixtures.registry(root, null, false);
        Path child = Files.createDirectories(root.resolve("project/nested"));
        var inherited = new EngineeringBoundary(root.resolve("home"), child);
        assertTrue(inherited.active());
        assertThrows(IOException.class, () -> inherited.checkTool("bash"));
        Path other = Files.createDirectories(root.resolve("other"));
        var legacy = new EngineeringBoundary(root.resolve("home"), other);
        assertFalse(legacy.active());
        assertDoesNotThrow(() -> legacy.checkTool("bash"));
        assertDoesNotThrow(
                () ->
                        legacy.checkInference(
                                "openai-api", URI.create("https://example.invalid"), null));
    }

    @Test
    void blocksToolDispatchAndMcpDiscoveryBeforeApprovalHandlersOrSubprocessEffects()
            throws Exception {
        EngineeringFixtures.registry(root, null, false);
        Path settings = root.resolve("home/.config/syleks/agent-config.json");
        Path marker = root.resolve("mcp-started");
        Files.writeString(
                settings,
                EngineeringFixtures.JSON.writeValueAsString(
                        Map.of(
                                "permission",
                                "allow",
                                "mcp",
                                Map.of(
                                        "sentinel",
                                        Map.of(
                                                "type",
                                                "stdio",
                                                "command",
                                                List.of("/usr/bin/touch", marker.toString()))))));
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var config = AppConfigFixtures.defaults(mapper.instance(), root);
            var executor = new ToolExecutor(config);
            var started = new AtomicInteger();
            try {
                for (String tool :
                        List.of(
                                "bash",
                                "webfetch",
                                "websearch",
                                "custom_send",
                                "mcp_sentinel__send")) {
                    var result =
                            executor.execute(
                                    "build",
                                    new ToolCall(
                                            "call", tool, Map.of("content", "synthetic private")),
                                    (operation, target, details) -> {
                                        started.incrementAndGet();
                                        return true;
                                    },
                                    started::incrementAndGet);
                    assertEquals(ToolResult.Status.DENIED, result.status(), tool);
                }
                assertEquals(0, started.get());
                try (var manager = new McpClientManager(config)) {
                    assertTrue(manager.catalogue().isEmpty());
                    assertEquals("Blocked by Engineering policy", manager.status().get("sentinel"));
                }
                assertFalse(Files.exists(marker));
                var local =
                        executor.execute(
                                "build", new ToolCall("local", "list", Map.of()), null, () -> {});
                assertEquals(ToolResult.Status.SUCCESS, local.status());
            } finally {
                executor.close();
            }
        }
    }

    @Test
    void providerServiceAndResponsesTransportRejectBeforeCallingAnyProviderOrCredentials()
            throws Exception {
        EngineeringFixtures.registry(root, null, false);
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var config = AppConfigFixtures.defaults(mapper.instance(), root);
            var client = new OllamaClient(config, mapper.instance());
            var registry =
                    new ProviderRegistry(null, null, null, null, null, null, null, config, client);
            var calls = new AtomicInteger();
            registry.register(
                    new LlmProvider() {
                        public String id() {
                            return "ollama";
                        }

                        public String displayName() {
                            return "fixture";
                        }

                        public ProviderCapabilities capabilities() {
                            return new ProviderCapabilities(true, true, true);
                        }

                        public List<LlmModel> listModels(String account) {
                            calls.incrementAndGet();
                            return List.of();
                        }

                        public void chat(ChatRequest request, ChatEventListener listener) {
                            calls.incrementAndGet();
                        }
                    });
            var service = new ProviderService(registry, null, null, config, mapper.instance());
            var request =
                    new ChatRequest(
                            "ollama/test",
                            "build",
                            null,
                            List.of(Map.of("role", "user", "content", "synthetic private")),
                            ToolHandler.NONE,
                            null);
            assertThrows(IOException.class, () -> service.stream(request, event -> {}));
            assertEquals(0, calls.get());
            var chatgpt = new ChatGptLlmProvider(null, mapper.instance(), config);
            assertTrue(
                    assertThrows(IOException.class, () -> chatgpt.chat(request, event -> {}))
                            .getMessage()
                            .contains("CLOUD_DISABLED"));
            assertEquals(
                    List.of(Proxy.NO_PROXY),
                    config.configureProxy("ollama", java.net.http.HttpClient.newBuilder())
                            .build()
                            .proxy()
                            .orElseThrow()
                            .select(URI.create("http://127.0.0.1")));
            client.close();
        }
    }
}
