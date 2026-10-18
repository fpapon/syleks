package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.app.Main;
import fr.openobject.ai.syleks.session.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CliSessionTest {
    @TempDir Path root;

    @Test void routesLegacyAndNewArgumentsAndRejectsAmbiguousOptions() {
        assertEquals("default", Main.parse(new String[0]).action());
        assertEquals("default", Main.parse(new String[]{"chat"}).action());
        for (String flag : List.of("-c", "--continue")) assertEquals("resume", Main.parse(new String[]{flag}).action());
        for (String flag : List.of("-s", "--session")) assertEquals("id", Main.parse(new String[]{"chat", flag, "id"}).id());
        assertEquals("fork", Main.parse(new String[]{"--fork", "id"}).action());
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[]{"--session"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[]{"--new", "--continue"}));
    }

    @Test void reopensAcrossTwoJvmsAndRunsRealFusionCliListExportDeleteAndHelp() throws Exception {
        String id = probe("create").strip();
        assertDoesNotThrow(() -> UUID.fromString(id));
        assertEquals(id, probe("resume").strip());
        var list = cli("session", "list");
        assertEquals(0, list.code(), list.output()); assertTrue(list.output().contains(id));
        var export = cli("session", "export", id);
        assertEquals(0, export.code(), export.output()); assertTrue(export.output().contains("second answer"));
        assertFalse(export.output().contains("apiKey"));
        var help = cli("--help"); assertEquals(0, help.code()); assertTrue(help.output().contains("--continue"));
        var bad = cli("session", "export", "../../escape"); assertEquals(2, bad.code());
        assertEquals(0, cli("session", "delete", id).code());
        assertFalse(cli("session", "list").output().contains(id));
        assertEquals(2, cli("--unknown").code());
    }

    @Test void recoversAfterActualProcessHaltAndReleasesItsSessionLock() throws Exception {
        String id = probe("create").strip();
        probe("crash", id);
        try (var f = new SessionFixture(root)) {
            f.agent.open(id);
            assertEquals(SessionStatus.INTERRUPTED, f.agent.current().status());
            assertEquals(MessageStatus.INTERRUPTED, f.conversation.messages(id).getLast().status());
            assertTrue(f.agent.history().getLast().content().contains("survives abrupt termination"));
            assertTrue(f.repositories.listExecutions(id).isEmpty());
            try (var lease = f.sessions.acquire(id)) { assertNotNull(lease); }
        }
    }

    @Test void coordinatesSessionLocksBetweenRealProcessesAndReleasesAfterKill() throws Exception {
        String id = probe("create").strip();
        Process holder = start("fr.openobject.ai.syleks.session.SessionProcess", root.toString(), "hold", id);
        try {
            var reader = holder.inputReader();
            assertEquals("LOCKED", reader.readLine());
            try (var f = new SessionFixture(root)) { assertThrows(IllegalStateException.class, () -> f.sessions.acquire(id)); }
        } finally { holder.destroyForcibly(); assertTrue(holder.waitFor(8, TimeUnit.SECONDS)); }
        try (var f = new SessionFixture(root); var lease = f.sessions.acquire(id)) { assertNotNull(lease); }
    }

    @Test void writesDifferentSessionsFromConcurrentProcessesWithoutCorruption() throws Exception {
        var one = start("fr.openobject.ai.syleks.session.SessionProcess", root.toString(), "write");
        var two = start("fr.openobject.ai.syleks.session.SessionProcess", root.toString(), "write");
        for (var process : List.of(one, two)) {
            assertTrue(process.waitFor(12, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        try (var f = new SessionFixture(root)) {
            assertEquals(2, f.sessions.list().size());
            assertEquals(40, f.sessions.list().stream().mapToInt(s -> f.conversation.messages(s.id()).size()).sum());
        }
    }

    private String probe(String... args) throws Exception {
        var all = new ArrayList<String>(); all.add(root.toString()); all.addAll(List.of(args));
        var process = start("fr.openobject.ai.syleks.session.SessionProcess", all.toArray(String[]::new));
        assertTrue(process.waitFor(12, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), error); return output;
    }
    private Result cli(String... args) throws Exception {
        var process = start("fr.openobject.ai.syleks.app.Main", args);
        assertTrue(process.waitFor(12, TimeUnit.SECONDS));
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                + new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    private Process start(String main, String... args) throws Exception {
        Files.createDirectories(root.resolve("project")); Files.createDirectories(root.resolve("home"));
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), main));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(root.resolve("project").toFile());
        builder.environment().put("SYLEKS_DATA_DIR", root.resolve("data").toString()); builder.environment().put("HOME", root.resolve("home").toString());
        builder.environment().remove("OLLAMA_MODEL"); builder.environment().remove("AGENT_SYSTEM_PROMPT");
        return builder.start();
    }
    private record Result(int code, String output) { }
}
