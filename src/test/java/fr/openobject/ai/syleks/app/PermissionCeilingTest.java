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

import static fr.openobject.ai.syleks.app.PermissionPolicy.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.session.SessionFixture;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionCeilingTest {
    @TempDir Path root;

    Path home() {
        return root.resolve("home");
    }

    Path project() {
        return root.resolve("project");
    }

    void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    void settings(String text) throws Exception {
        write(home().resolve(".config/syleks/agent-config.json"), text);
    }

    void agent(String text) throws Exception {
        write(
                project().resolve(".syleks/agents/build.md"),
                "---\n" + text + "\n---\nProject guidance");
    }

    void trust() throws Exception {
        var review = new ProjectTrust(home(), project());
        review.grant(review.digest());
    }

    @Test
    void combinesEveryPairAndTreatsMissingCeilingRulesAsNeutral() {
        for (var preference : PermissionPolicy.Decision.values()) {
            for (var ceiling : PermissionPolicy.Decision.values()) {
                var policy =
                        new PermissionPolicy(
                                        List.of(new PermissionPolicy.Rule("read", "*", preference)))
                                .intersect(
                                        new PermissionPolicy(
                                                        List.of(
                                                                new PermissionPolicy.Rule(
                                                                        "read", "*", ceiling)))
                                                .neutral());
                assertEquals(
                        PermissionPolicy.stricter(preference, ceiling),
                        policy.evaluate("read", "file"));
            }
        }
        assertEquals(ALLOW, PermissionPolicy.empty().neutral().evaluate("read", "file"));
        assertEquals(DENY, PermissionPolicy.empty().evaluate("read", "file"));
    }

    @Test
    void globalDenySurvivesProjectTrustRestartAndAnAlwaysApprovingCallback() throws Exception {
        settings("{\"permission\":{\"bash\":\"deny\",\"write\":\"deny\"}}");
        agent("permission: \"allow\"");
        for (boolean trusted : List.of(false, true)) {
            if (trusted) trust();
            try (var f = new SessionFixture(root)) {
                assertEquals(
                        DENY, f.config.permissions("build").evaluate("bash", "touch forbidden"));
                var executor = new ToolExecutor(f.config);
                var result =
                        executor.execute(
                                "build",
                                new ToolCall(
                                        "shell",
                                        "bash",
                                        Map.of("command", "touch prefix; touch forbidden")),
                                (op, target, details) -> true,
                                () -> fail("Denied effect started"));
                assertEquals(ToolResult.Status.DENIED, result.status());
                result =
                        executor.execute(
                                "build",
                                new ToolCall(
                                        "file",
                                        "write",
                                        Map.of("path", "forbidden", "content", "sentinel")),
                                (op, target, details) -> true,
                                () -> fail("Denied write started"));
                assertEquals(ToolResult.Status.DENIED, result.status());
                assertFalse(Files.exists(project().resolve("prefix")));
                assertFalse(Files.exists(project().resolve("forbidden")));
            }
        }
    }

    @Test
    void globalAskStillRequiresApprovalAndUserAgentCapabilitiesSurviveBuiltInDefaults()
            throws Exception {
        settings(
                "{\"permission\":{\"bash\":\"ask\"},\"agent\":{\"build\":{\"permission\":{\"read\":\"allow\"}}}}");
        agent("permission: \"allow\"");
        trust();
        try (var f = new SessionFixture(root)) {
            assertEquals(ASK, f.config.permissions("build").evaluate("bash", "touch approved"));
            assertEquals(ALLOW, f.config.permissions("build").evaluate("read", "file"));
            var executor = new ToolExecutor(f.config);
            var call = new ToolCall("shell", "bash", Map.of("command", "touch approved"));
            assertEquals(
                    ToolResult.Status.DENIED,
                    executor.execute("build", call, null, () -> {}).status());
            assertFalse(Files.exists(project().resolve("approved")));
            assertEquals(
                    ToolResult.Status.SUCCESS,
                    executor.execute("build", call, (op, target, details) -> true, () -> {})
                            .status());
            assertTrue(Files.exists(project().resolve("approved")));
        }
    }

    @Test
    void untrustedProjectCannotExpandDefaultsOrChangeModelsAndTrustIsContentBound()
            throws Exception {
        settings("{}");
        agent("model: project-model\npermission:\n  read: allow");
        try (var f = new SessionFixture(root)) {
            assertEquals(DENY, f.config.permissions("build").evaluate("read", "file"));
            assertNull(f.config.agent("build").model());
        }
        trust();
        try (var f = new SessionFixture(root)) {
            assertEquals(ALLOW, f.config.permissions("build").evaluate("read", "file"));
            assertEquals("project-model", f.config.agent("build").model());
        }
        var old = new ProjectTrust(home(), project());
        write(project().resolve(".syleks/agents/new.md"), "New agent");
        var changed = new ProjectTrust(home(), project());
        assertFalse(changed.trusted());
        assertThrows(IllegalArgumentException.class, () -> changed.grant(old.digest()));
        trust();
        agent("permission:\n  read: allow\n  write: allow");
        try (var f = new SessionFixture(root)) {
            assertFalse(f.config.projectTrust().trusted());
            assertEquals(DENY, f.config.permissions("build").evaluate("write", "file"));
        }
        trust();
        new ProjectTrust(home(), project()).revoke();
        assertFalse(new ProjectTrust(home(), project()).trusted());
    }

    @Test
    void canonicalAliasesShareTrustButEscapingAgentLinksAreRejected() throws Exception {
        settings("{}");
        agent("permission:\n  read: allow");
        trust();
        Path alias = Files.createSymbolicLink(root.resolve("alias"), project());
        assertTrue(new ProjectTrust(home(), alias).trusted());
        write(root.resolve("outside.md"), "Outside");
        Files.createSymbolicLink(
                project().resolve(".syleks/agents/escape.md"), root.resolve("outside.md"));
        assertThrows(IllegalStateException.class, () -> new ProjectTrust(home(), project()));
    }

    @Test
    void orderedCeilingShellAllowlistChecksAllActionsBeforeEffects() throws Exception {
        settings(
                "{\"permission\":{\"bash\":{\"*\":\"deny\",\"touch permitted\":\"allow\",\"touch review*\":\"ask\",\"touch review-secret\":\"deny\"}}}");
        agent("permission:\n  bash: allow");
        trust();
        try (var f = new SessionFixture(root)) {
            var policy = f.config.permissions("build");
            assertEquals(ALLOW, policy.evaluate("bash", "touch permitted"));
            assertEquals(ASK, policy.evaluate("bash", "touch review-file"));
            assertEquals(DENY, policy.evaluate("bash", "touch review-secret"));
            var result =
                    new ToolExecutor(f.config)
                            .execute(
                                    "build",
                                    new ToolCall(
                                            "shell",
                                            "bash",
                                            Map.of("command", "touch permitted; touch forbidden")),
                                    (op, target, details) -> true,
                                    () -> fail("Prefix must not run"));
            assertEquals(ToolResult.Status.DENIED, result.status());
            assertFalse(Files.exists(project().resolve("permitted")));
            assertFalse(Files.exists(project().resolve("forbidden")));
        }
    }

    @Test
    void legacyMcpPathTrustDoesNotAuthorizeProjectIntegrations() throws Exception {
        Files.createDirectories(project());
        settings("{\"mcp_trusted_projects\":[\"" + project() + "\"]}");
        write(
                project().resolve(".syleks/agent-config.json"),
                "{\"mcp\":{\"fixture\":{\"type\":\"stdio\",\"command\":[\"never-launch\"],\"enabled\":false}}}");
        try (var f = new SessionFixture(root)) {
            assertTrue(f.config.mcpServers().isEmpty());
        }
        trust();
        try (var f = new SessionFixture(root)) {
            assertTrue(f.config.mcpServers().containsKey("fixture"));
        }
        new ProjectTrust(home(), project()).revoke();
        try (var f = new SessionFixture(root)) {
            assertTrue(f.config.mcpServers().isEmpty());
        }
    }

    @Test
    void mutationToolsCannotWriteGlobalPolicyOrTrustEvenWhenHomeIsTheWorkspace() throws Exception {
        settings("{\"permission\":\"allow\"}");
        Path target = home().resolve(".config/syleks/project-trust/forged");
        Files.createDirectories(target.getParent());
        try (var f = new SessionFixture(root, home())) {
            var result =
                    new ToolExecutor(f.config)
                            .execute(
                                    "build",
                                    new ToolCall(
                                            "file",
                                            "write",
                                            Map.of("path", target.toString(), "content", "forged")),
                                    (op, path, details) -> true,
                                    () -> fail("Security mutation started"));
            assertEquals(ToolResult.Status.DENIED, result.status());
            assertFalse(Files.exists(target));
            assertTrue(f.config.securityFile(target));
            Path alias = Files.createSymbolicLink(root.resolve("config-alias"), target.getParent());
            assertTrue(f.config.securityFile(alias.resolve("forged")));
        }
    }

    @Test
    void nestedDefinitionsCannotRelaxInheritedAgentConstraintsAndChangesNeedReview()
            throws Exception {
        settings("{\"permission\":{\"read\":\"allow\"}}");
        Files.createDirectories(project());
        var git = new ProcessBuilder("git", "init", "-q", project().toString()).start();
        assertEquals(0, git.waitFor());
        agent("permission:\n  read:\n    \"*\": deny\n    \"safe*\": ask");
        var nested = Files.createDirectories(project().resolve("module"));
        write(
                nested.resolve(".syleks/agents/build.md"),
                "---\npermission:\n  read: allow\n---\nNested");
        var review = new ProjectTrust(home(), nested);
        review.grant(review.digest());
        try (var f = new SessionFixture(root, nested)) {
            assertEquals(DENY, f.config.permissions("build").evaluate("read", "secret"));
            assertEquals(ASK, f.config.permissions("build").evaluate("read", "safe-file"));
        }
        write(project().resolve(".syleks/agents/added.md"), "New definition");
        assertFalse(new ProjectTrust(home(), nested).trusted());
    }

    @Test
    void reproducesTheOldOverlayExpansionAndReportsTheEnforcedCeilingSource() throws Exception {
        var global = PermissionPolicy.parse(Map.of("bash", "deny"), root.resolve("global.json"));
        var projectRules =
                PermissionPolicy.parse(Map.of("bash", "allow"), root.resolve("project.md"));
        assertEquals(
                ALLOW,
                PermissionPolicy.defaults()
                        .overlay(global)
                        .overlay(projectRules)
                        .evaluate("bash", "touch marker"));
        settings("{\"permission\":{\"bash\":\"deny\"}}");
        agent("permission:\n  bash: allow");
        trust();
        try (var f = new SessionFixture(root)) {
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.listPermissions("build");
            var output = chat.history().getLast().content();
            assertTrue(output.contains("bash: DENY"));
            assertTrue(output.contains("trusted workspace"));
            assertTrue(
                    output.contains(
                            "ceiling [DENY; source: "
                                    + home().resolve(".config/syleks/agent-config.json")));
            assertTrue(output.contains(project().resolve(".syleks/agents/build.md").toString()));
        }
    }

    @Test
    void revokesWithoutReadingChangedDefinitionsAndKeepsPreviouslyCapturedContentImmutable()
            throws Exception {
        settings("{}");
        agent("permission:\n  read: allow");
        trust();
        var snapshot = new ProjectTrust(home(), project());
        var file = project().resolve(".syleks/agents/build.md");
        var original = snapshot.content(file);
        Files.write(file, new byte[] {(byte) 0xff});
        assertEquals(original, snapshot.content(file));
        assertThrows(IllegalStateException.class, () -> new ProjectTrust(home(), project()));
        ProjectTrust.revoke(home(), project());
        Files.writeString(file, original);
        assertFalse(new ProjectTrust(home(), project()).trusted());
    }
}
