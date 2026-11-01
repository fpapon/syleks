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
package fr.openobject.ai.syleks.session;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.app.Main;
import fr.openobject.ai.syleks.session.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSessionTest {
    @TempDir Path root;

    @Test
    void routesLegacyAndNewArgumentsAndRejectsAmbiguousOptions() {
        assertEquals("default", Main.parse(new String[0]).action());
        assertEquals("default", Main.parse(new String[] {"chat"}).action());
        for (String flag : List.of("-c", "--continue"))
            assertEquals("resume", Main.parse(new String[] {flag}).action());
        for (String flag : List.of("-s", "--session"))
            assertEquals("id", Main.parse(new String[] {"chat", flag, "id"}).id());
        assertEquals("fork", Main.parse(new String[] {"--fork", "id"}).action());
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {"--session"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> Main.parse(new String[] {"--new", "--continue"}));
    }

    @Test
    void reopensAcrossTwoJvmsAndRunsRealFusionCliListExportDeleteAndHelp() throws Exception {
        String id = probe("create").strip();
        assertDoesNotThrow(() -> UUID.fromString(id));
        assertEquals(id, probe("resume").strip());
        var list = cli("session", "list");
        assertEquals(0, list.code(), list.output());
        assertTrue(list.output().contains(id));
        var export = cli("session", "export", id);
        assertEquals(0, export.code(), export.output());
        assertTrue(export.output().contains("second answer"));
        assertFalse(export.output().contains("apiKey"));
        var help = cli("--help");
        assertEquals(0, help.code());
        assertTrue(help.output().contains("--continue"));
        var bad = cli("session", "export", "../../escape");
        assertEquals(2, bad.code());
        assertEquals(0, cli("session", "delete", id).code());
        assertFalse(cli("session", "list").output().contains(id));
        assertEquals(2, cli("--unknown").code());
    }

    @Test
    void recoversAfterActualProcessHaltAndReleasesItsSessionLock() throws Exception {
        String id = probe("create").strip();
        probe("crash", id);
        try (var f = new SessionFixture(root)) {
            f.agent.open(id);
            assertEquals(SessionStatus.INTERRUPTED, f.agent.current().status());
            assertEquals(MessageStatus.INTERRUPTED, f.conversation.messages(id).getLast().status());
            assertTrue(
                    f.agent.history().getLast().content().contains("survives abrupt termination"));
            assertTrue(f.repositories.listExecutions(id).isEmpty());
            try (var lease = f.sessions.acquire(id)) {
                assertNotNull(lease);
            }
        }
    }

    @Test
    void coordinatesSessionLocksBetweenRealProcessesAndReleasesAfterKill() throws Exception {
        String id = probe("create").strip();
        Process holder =
                start(
                        "fr.openobject.ai.syleks.session.SessionProcess",
                        root.toString(),
                        "hold",
                        id);
        try {
            var reader = holder.inputReader();
            assertEquals("LOCKED", reader.readLine());
            try (var f = new SessionFixture(root)) {
                assertThrows(IllegalStateException.class, () -> f.sessions.acquire(id));
            }
        } finally {
            holder.destroyForcibly();
            assertTrue(holder.waitFor(8, TimeUnit.SECONDS));
        }
        try (var f = new SessionFixture(root);
                var lease = f.sessions.acquire(id)) {
            assertNotNull(lease);
        }
    }

    @Test
    void writesDifferentSessionsFromConcurrentProcessesWithoutCorruption() throws Exception {
        var one = start("fr.openobject.ai.syleks.session.SessionProcess", root.toString(), "write");
        var two = start("fr.openobject.ai.syleks.session.SessionProcess", root.toString(), "write");
        for (var process : List.of(one, two)) {
            assertTrue(process.waitFor(12, TimeUnit.SECONDS));
            assertEquals(
                    0,
                    process.exitValue(),
                    new String(
                            process.getErrorStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        try (var f = new SessionFixture(root)) {
            assertEquals(2, f.sessions.list().size());
            assertEquals(
                    40,
                    f.sessions.list().stream()
                            .mapToInt(s -> f.conversation.messages(s.id()).size())
                            .sum());
        }
    }

    @Test
    void initializesConfigurationThroughFusionWithoutOpeningTheSessionDatabase() throws Exception {
        var initialized = cli("config", "init", "--defaults");
        assertEquals(0, initialized.code(), initialized.output());
        assertTrue(initialized.output().contains("Created "));
        var config = root.resolve("home/.config/syleks/agent-config.json");
        assertTrue(Files.isRegularFile(config));
        assertTrue(Files.isRegularFile(root.resolve("home/.config/syleks/agents/build.md")));
        assertFalse(Files.exists(root.resolve("data")));
        Files.writeString(config, "a custom config that cannot be parsed");
        var repeated = cli("config", "init");
        assertEquals(0, repeated.code(), repeated.output());
        assertTrue(repeated.output().contains("Skipped existing "));
        assertEquals("a custom config that cannot be parsed", Files.readString(config));
        assertFalse(Files.exists(root.resolve("data")));
        assertEquals(2, cli("config", "init", "--force").code());
        assertTrue(cli("--help").output().contains("config init"));
    }

    @Test
    void asksForSettingsAndWritesTheAnswersWithoutEchoingTheApiKey() throws Exception {
        var process = start("fr.openobject.ai.syleks.app.Main", "config", "init");
        try (var input = process.outputWriter(java.nio.charset.StandardCharsets.UTF_8)) {
            input.write(
                    String.join(
                                    "\n",
                                    List.of(
                                            "team",
                                            "Team server",
                                            "http://localhost:8000/v1",
                                            "test-secret-key",
                                            "test-model",
                                            "Test model",
                                            "16384",
                                            "2048",
                                            "20",
                                            "0.2",
                                            "30",
                                            "0.1",
                                            "30",
                                            "system",
                                            "false"))
                            + "\n");
        }
        assertTrue(process.waitFor(12, TimeUnit.SECONDS));
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        String error =
                new String(
                        process.getErrorStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), error);
        assertTrue(output.contains("Provider ID"));
        assertFalse(output.contains("test-secret-key"));
        var config = Files.readString(root.resolve("home/.config/syleks/agent-config.json"));
        assertTrue(config.contains("team/test-model"));
        assertTrue(config.contains("test-secret-key"));
        assertFalse(Files.exists(root.resolve("data")));
    }

    @Test
    void cancelsInitializationOnEofBeforeCreatingFiles() throws Exception {
        var process = start("fr.openobject.ai.syleks.app.Main", "config", "init");
        process.getOutputStream().close();
        assertTrue(process.waitFor(12, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
        assertFalse(Files.exists(root.resolve("home/.config/syleks")));
        assertTrue(
                new String(
                                process.getErrorStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)
                        .contains("cancelled"));
    }

    @Test
    void exposesImportPrefixForkMessageIdsAndFileHistoryCommandsThroughRealCli() throws Exception {
        String id = probe("create").strip();
        var exported = cli("session", "export", id);
        assertEquals(0, exported.code(), exported.output());
        Path archive = root.resolve("archive.json");
        Files.writeString(archive, exported.stdout());
        var imported = cli("session", "import", archive.toString());
        assertEquals(0, imported.code(), imported.output());
        String copy = imported.stdout().strip();
        assertDoesNotThrow(() -> UUID.fromString(copy));
        assertNotEquals(id, copy);
        var messageList = cli("session", "messages", id);
        assertEquals(0, messageList.code());
        String message =
                messageList
                        .output()
                        .lines()
                        .filter(line -> line.contains("\tuser\t"))
                        .findFirst()
                        .orElseThrow()
                        .split("\t")[0];
        var fork = cli("session", "fork", id, message);
        assertEquals(0, fork.code(), fork.output());
        String forkId = fork.stdout().strip();
        assertDoesNotThrow(() -> UUID.fromString(forkId));
        var prefix = cli("session", "export", forkId);
        assertFalse(prefix.output().contains("persisted answer"));
        assertEquals(0, cli("session", "changes", id).code());
        assertEquals(0, cli("session", "diff", id).code());
        assertEquals(1, cli("session", "undo", id).code());
        Files.writeString(archive, "{\"schemaVersion\":99}");
        assertEquals(2, cli("session", "import", archive.toString()).code());
        assertTrue(cli("--help").output().contains("message-id"));
    }

    private String probe(String... args) throws Exception {
        var all = new ArrayList<String>();
        all.add(root.toString());
        all.addAll(List.of(args));
        var process =
                start("fr.openobject.ai.syleks.session.SessionProcess", all.toArray(String[]::new));
        assertTrue(process.waitFor(12, TimeUnit.SECONDS));
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        String error =
                new String(
                        process.getErrorStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), error);
        return output;
    }

    private Result cli(String... args) throws Exception {
        var process = start("fr.openobject.ai.syleks.app.Main", args);
        assertTrue(process.waitFor(12, TimeUnit.SECONDS));
        String stdout =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        String stderr =
                new String(
                        process.getErrorStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        return new Result(process.exitValue(), stdout + stderr, stdout);
    }

    private Process start(String main, String... args) throws Exception {
        Files.createDirectories(root.resolve("project"));
        Files.createDirectories(root.resolve("home"));
        var command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "--enable-native-access=ALL-UNNAMED",
                                "-cp",
                                System.getProperty("java.class.path"),
                                main));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(root.resolve("project").toFile());
        builder.environment().put("SYLEKS_DATA_DIR", root.resolve("data").toString());
        builder.environment().put("HOME", root.resolve("home").toString());
        builder.environment().put("LC_ALL", "en_US.UTF-8");
        builder.environment().put("LANG", "en_US.UTF-8");
        builder.environment().remove("OLLAMA_MODEL");
        builder.environment().remove("AGENT_SYSTEM_PROMPT");
        return builder.start();
    }

    private record Result(int code, String output, String stdout) {}
}
