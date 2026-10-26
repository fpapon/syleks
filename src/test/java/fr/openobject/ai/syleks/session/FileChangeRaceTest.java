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

import fr.openobject.ai.syleks.fs.SecureFiles;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileChangeRaceTest {
    @TempDir Path root;

    @Test
    void undoRedoDetachesAliasesAndRejectsChangesDuringApproval() throws Exception {
        try (var f = new SessionFixture(root)) {
            var id = f.sessions.create("qwen3:8b", "Ollama").id();
            var journal = f.sessions.fileChanges();
            var file = root.resolve("project/file");
            var before = "before".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var after = "after".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(file, before);
            journal.recording(id, "edit")
                    .commit(file, before, after, () -> SecureFiles.commit(file, before, after));
            var alias = Files.createLink(root.resolve("outside"), file);
            journal.restore(id, false, (op, target, details) -> true);
            assertArrayEquals(before, Files.readAllBytes(file));
            assertArrayEquals(after, Files.readAllBytes(alias));
            journal.restore(id, true, (op, target, details) -> true);
            assertArrayEquals(after, Files.readAllBytes(file));
            assertThrows(
                    Exception.class,
                    () ->
                            journal.restore(
                                    id,
                                    false,
                                    (op, target, details) -> {
                                        try {
                                            Files.writeString(file, "external");
                                        } catch (Exception e) {
                                            throw new IllegalStateException(e);
                                        }
                                        return true;
                                    }));
            assertEquals("external", Files.readString(file));
            assertArrayEquals(after, Files.readAllBytes(alias));
        }
    }

    @Test
    void failedJournalEffectIsRecoveredWithoutReplayOrOutsideWrite() throws Exception {
        try (var f = new SessionFixture(root)) {
            var id = f.sessions.create("qwen3:8b", "Ollama").id();
            var journal = f.sessions.fileChanges();
            var folder = Files.createDirectory(root.resolve("project/nested"));
            var file = folder.resolve("file");
            var before = "before".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var after = "after".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(file, before);
            var outside = Files.createDirectory(root.resolve("outside"));
            Files.write(outside.resolve("file"), before);
            assertThrows(
                    Exception.class,
                    () ->
                            journal.recording(id, "edit")
                                    .commit(
                                            file,
                                            before,
                                            after,
                                            () -> {
                                                Files.move(folder, root.resolve("moved"));
                                                Files.createSymbolicLink(folder, outside);
                                                SecureFiles.commit(file, before, after);
                                            }));
            journal.recover(id);
            assertEquals("CONFLICT", journal.list(id).getFirst().state());
            assertThrows(
                    Exception.class,
                    () -> journal.restore(id, false, (op, target, details) -> true));
            assertArrayEquals(before, Files.readAllBytes(outside.resolve("file")));
        }
    }
}
