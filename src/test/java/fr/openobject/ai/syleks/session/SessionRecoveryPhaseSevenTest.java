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

import fr.openobject.ai.syleks.session.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionRecoveryPhaseSevenTest {
    @TempDir Path root;

    @Test
    void recoversFourActualProcessCrashBoundariesWithoutReplayingEffects() throws Exception {
        for (String boundary : List.of("before", "during", "received", "committed")) {
            Path scenario = root.resolve(boundary);
            Files.createDirectories(scenario.resolve("project"));
            var process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                    "--enable-native-access=ALL-UNNAMED",
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    SessionProcess.class.getName(),
                                    scenario.toString(),
                                    "phase7-crash",
                                    boundary)
                            .start();
            try {
                assertTrue(process.waitFor(12, TimeUnit.SECONDS));
                String output =
                        new String(
                                        process.getInputStream().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .strip();
                assertEquals(
                        0,
                        process.exitValue(),
                        new String(
                                process.getErrorStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8));
                String id = UUID.fromString(output).toString();
                try (var f = new SessionFixture(scenario)) {
                    f.agent.open(id);
                    var execution = f.repositories.listExecutions(id).getFirst();
                    assertEquals(
                            boundary.equals("committed")
                                    ? ToolStatus.SUCCESS
                                    : ToolStatus.INTERRUPTED,
                            execution.status(),
                            boundary);
                    String context =
                            f.context.reconstruct(f.agent.current(), "build").messages().toString();
                    assertEquals(
                            boundary.equals("committed"), context.contains("tool_calls"), boundary);
                    if (!boundary.equals("committed"))
                        assertTrue(context.contains("external effect is unknown"), context);
                    var journal = f.sessions.fileChanges().list(id);
                    if (boundary.equals("before")) {
                        assertTrue(journal.isEmpty());
                        assertFalse(Files.exists(scenario.resolve("effects.log")));
                    } else {
                        assertEquals("APPLIED", journal.getFirst().state());
                        assertEquals(
                                List.of("effect"),
                                Files.readAllLines(scenario.resolve("effects.log")));
                    }
                    try (var g = f.agent.begin("qwen3:8b", "build", "continue safely", false)) {
                        g.append("No automatic replay");
                        g.finish(MessageStatus.COMPLETE);
                    }
                    f.sessions.list();
                    f.agent.open(id);
                    if (!boundary.equals("before"))
                        assertEquals(
                                List.of("effect"),
                                Files.readAllLines(scenario.resolve("effects.log")));
                }
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void observesPendingRestoreAndExternalConflictWithoutWritingFiles() throws Exception {
        try (var f = new SessionFixture(root)) {
            String id = f.sessions.create("qwen3:8b", "Ollama").id();
            Path file = root.resolve("project/change");
            Files.writeString(file, "external");
            var pending =
                    new FileChangeJournal.Change(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            id,
                            "call",
                            file.toString(),
                            "before".getBytes(),
                            "after".getBytes(),
                            "PENDING_UNDO",
                            java.time.Instant.now().toString());
            f.sessions.fileChanges().insert(pending);
            f.sessions.recover(id);
            assertEquals("CONFLICT", f.sessions.fileChanges().list(id).getFirst().state());
            assertEquals("external", Files.readString(file));
            assertThrows(
                    java.io.IOException.class,
                    () -> f.sessions.restoreFiles(id, false, (o, t, d) -> true));
        }
    }
}
