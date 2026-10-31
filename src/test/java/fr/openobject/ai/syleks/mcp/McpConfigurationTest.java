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

import fr.openobject.ai.syleks.json.JsonIO;
import fr.openobject.ai.syleks.json.SchemaValidator;
import fr.openobject.ai.syleks.session.SessionFixture;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigurationTest {
    @TempDir Path root;

    @Test
    void rejectsMalformedConfigurationAndValidatesSchema() throws Exception {
        var json = new JsonIO();
        var schema =
                new SchemaValidator(
                        new JsonIO().parse(Path.of("agent-config.schema.json").toFile()));
        for (String invalid :
                List.of(
                        "{\"type\":\"sse\",\"url\":\"http://localhost/mcp\"}",
                        "{\"type\":\"stdio\",\"command\":[]}",
                        "{\"type\":\"http\",\"url\":\"file:///tmp/mcp\"}",
                        "{\"type\":\"http\",\"url\":\"http://user:secret@localhost/mcp\"}",
                        "{\"type\":\"http\",\"url\":\"http://localhost/mcp\",\"timeout_seconds\":1.5}",
                        "{\"type\":\"stdio\",\"command\":[\"server\"],\"enabled\":null}",
                        "{\"type\":\"stdio\",\"command\":[\"server\"],\"unknown\":true}")) {
            Map<?, ?> value = json.read(invalid, Map.class);
            assertThrows(
                    IllegalStateException.class,
                    () -> McpServerConfig.parse(Map.of("fixture", value), root),
                    invalid);
            assertFalse(
                    schema.validate(json.parse("{\"mcp\":{\"fixture\":" + invalid + "}}"))
                            .isEmpty(),
                    invalid);
        }
        for (String invalid : List.of("Authorization", "Accept", "Mcp-Session-Id")) {
            var server =
                    new HashMap<String, Object>(
                            Map.of(
                                    "type",
                                    "http",
                                    "url",
                                    "https://mcp.example/mcp",
                                    "headers",
                                    Map.of(invalid, "value")));
            if (invalid.equals("Authorization")) server.put("bearer_token_env", "MCP_TOKEN");
            assertThrows(
                    IllegalStateException.class,
                    () -> McpServerConfig.parse(Map.of("fixture", server), root));
        }
        String valid =
                "{\"mcp\":{\"remote\":{\"type\":\"http\",\"url\":\"https://mcp.example/mcp\",\"bearer_token_env\":\"MCP_TOKEN\",\"resources\":true}},\"permission\":{\"mcp_*\":\"ask\",\"mcp_remote__t_echo\":\"allow\"}}";
        assertTrue(schema.validate(json.parse(valid)).isEmpty());
    }

    @Test
    void mergesProjectServersAndDoesNotLaunchDisabledProcesses() throws Exception {
        Path global = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(global.getParent());
        Files.writeString(
                global,
                "{\"mcp_trusted_projects\":[\""
                        + root.resolve("project")
                        + "\"],\"mcp\":{\"one\":{\"type\":\"stdio\",\"command\":[\"does-not-exist\"]},\"two\":{\"type\":\"stdio\",\"command\":[\"does-not-exist\"],\"enabled\":false}}}");
        Path project = root.resolve("project/.syleks/agent-config.json");
        Files.createDirectories(project.getParent());
        Files.writeString(
                project,
                "{\"mcp\":{\"one\":{\"type\":\"stdio\",\"command\":[\"does-not-exist\"],\"enabled\":false}}}");
        var reviewed =
                new fr.openobject.ai.syleks.app.ProjectTrust(
                        root.resolve("home"), root.resolve("project"));
        reviewed.grant(reviewed.digest());
        try (var f = new SessionFixture(root);
                var manager = new McpClientManager(f.config)) {
            assertEquals(2, f.config.mcpServers().size());
            assertTrue(manager.catalogue().isEmpty());
            assertTrue(manager.status().values().stream().allMatch("Disabled"::equals));
        }
    }

    @Test
    void ignoresUntrustedProjectServerDefinitions() throws Exception {
        Path project = root.resolve("project/.syleks/agent-config.json");
        Files.createDirectories(project.getParent());
        Files.writeString(
                project,
                "{\"mcp\":{\"untrusted\":{\"type\":\"stdio\",\"command\":[\"untrusted-program\"]}}}");
        try (var f = new SessionFixture(root);
                var manager = new McpClientManager(f.config)) {
            assertTrue(f.config.mcpServers().isEmpty());
            assertTrue(manager.catalogue().isEmpty());
        }
    }
}
