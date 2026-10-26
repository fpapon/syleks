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
package fr.openobject.ai.syleks.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureFilesTest {
    @TempDir Path temporary;
    private static final byte[] OLD = "old".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] NEW = "new".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void replacementDetachesHardLinksAndPreservesPermissions() throws Exception {
        var file = temporary.resolve("file");
        var alias = temporary.resolve("outside");
        Files.write(file, OLD);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        Files.createLink(alias, file);
        SecureFiles.commit(file, OLD, NEW);
        assertArrayEquals(OLD, Files.readAllBytes(alias));
        assertArrayEquals(NEW, SecureFiles.read(file, 100));
        assertEquals(
                PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(file));
        SecureFiles.commit(file, NEW, null);
        assertFalse(Files.exists(file));
        assertArrayEquals(OLD, Files.readAllBytes(alias));
    }

    @Test
    void finalSymlinkSwapCannotChangeOutsideMarker() throws Exception {
        var file = temporary.resolve("file");
        var outside = temporary.resolve("outside");
        Files.write(file, OLD);
        Files.write(outside, OLD);
        try (var parent = SecureFiles.parent(file)) {
            assertThrows(
                    IOException.class,
                    () ->
                            parent.commit(
                                    OLD,
                                    NEW,
                                    () ->
                                            unchecked(
                                                    () -> {
                                                        Files.delete(file);
                                                        Files.createSymbolicLink(file, outside);
                                                    })));
        }
        assertArrayEquals(OLD, Files.readAllBytes(outside));
        try (var entries = Files.list(temporary)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".syleks-")));
        }
    }

    @Test
    void parentSwapUsesPinnedDirectoryForReadAndReplacement() throws Exception {
        var folder = Files.createDirectory(temporary.resolve("workspace"));
        var outside = Files.createDirectory(temporary.resolve("outside"));
        var moved = temporary.resolve("moved");
        Files.write(folder.resolve("file"), OLD);
        Files.write(outside.resolve("file"), OLD);
        try (var parent = SecureFiles.parent(folder.resolve("file"))) {
            parent.commit(
                    OLD,
                    NEW,
                    () ->
                            unchecked(
                                    () -> {
                                        Files.move(folder, moved);
                                        Files.createSymbolicLink(folder, outside);
                                    }));
            assertArrayEquals(NEW, parent.read(100));
        }
        assertArrayEquals(OLD, Files.readAllBytes(outside.resolve("file")));
        assertArrayEquals(NEW, Files.readAllBytes(moved.resolve("file")));
        assertThrows(IOException.class, () -> SecureFiles.read(folder.resolve("file"), 100));
    }

    @Test
    void newFileDoesNotOverwriteEntryCreatedAtCommit() throws Exception {
        var file = temporary.resolve("new");
        try (var parent = SecureFiles.parent(file)) {
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> parent.commit(null, NEW, () -> unchecked(() -> Files.write(file, OLD))));
        }
        assertArrayEquals(OLD, Files.readAllBytes(file));
    }

    @Test
    void newFileUsesPinnedParentAndPrivatePermissions() throws Exception {
        var folder = Files.createDirectory(temporary.resolve("workspace"));
        var outside = Files.createDirectory(temporary.resolve("outside"));
        try (var parent = SecureFiles.parent(folder.resolve("new"))) {
            parent.commit(
                    null,
                    NEW,
                    () ->
                            unchecked(
                                    () -> {
                                        Files.move(folder, temporary.resolve("moved"));
                                        Files.createSymbolicLink(folder, outside);
                                    }));
        }
        assertFalse(Files.exists(outside.resolve("new")));
        assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(temporary.resolve("moved/new")));
    }

    @Test
    void changedContentAndIdentityAreRejected() throws Exception {
        var file = temporary.resolve("file");
        Files.write(file, OLD);
        try (var parent = SecureFiles.parent(file)) {
            assertThrows(
                    IOException.class,
                    () ->
                            parent.commit(
                                    OLD,
                                    NEW,
                                    () -> unchecked(() -> Files.writeString(file, "external"))));
        }
        assertEquals("external", Files.readString(file));
        Files.write(file, OLD);
        try (var parent = SecureFiles.parent(file)) {
            assertThrows(
                    IOException.class,
                    () ->
                            parent.commit(
                                    OLD,
                                    NEW,
                                    () ->
                                            unchecked(
                                                    () -> {
                                                        Files.move(
                                                                file,
                                                                temporary.resolve("original"));
                                                        Files.write(file, OLD);
                                                    })));
        }
        assertArrayEquals(OLD, Files.readAllBytes(file));
    }

    @Test
    void cancellationHasNoEffect() throws Exception {
        var file = temporary.resolve("file");
        Files.write(file, OLD);
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> SecureFiles.commit(file, OLD, NEW));
        } finally {
            Thread.interrupted();
        }
        assertArrayEquals(OLD, Files.readAllBytes(file));
    }

    @Test
    void unsupportedProviderFailsClosed() throws Exception {
        var uri = java.net.URI.create("jar:" + temporary.resolve("test.zip").toUri());
        try (var zip = FileSystems.newFileSystem(uri, java.util.Map.of("create", "true"))) {
            assertThrows(
                    IOException.class, () -> SecureFiles.commit(zip.getPath("/file"), null, NEW));
            assertFalse(Files.exists(zip.getPath("/file")));
        }
    }

    @Test
    void failedAtomicMoveLeavesOriginalAndCleansStaging() throws Exception {
        var file = temporary.resolve("file");
        Files.write(file, OLD);
        try (var parent = SecureFiles.parent(file)) {
            assertThrows(
                    AtomicMoveNotSupportedException.class,
                    () ->
                            parent.commit(
                                    OLD,
                                    NEW,
                                    () -> {},
                                    (source, target) -> {
                                        throw new AtomicMoveNotSupportedException(
                                                source.toString(), target.toString(), "fixture");
                                    }));
        }
        assertArrayEquals(OLD, Files.readAllBytes(file));
        try (var entries = Files.list(temporary)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void deterministicLegacyPathWriteReproducesParentRace() throws Exception {
        var folder = Files.createDirectory(temporary.resolve("workspace"));
        var outside = Files.createDirectory(temporary.resolve("outside"));
        var file = folder.resolve("file");
        Files.write(file, OLD);
        Files.write(outside.resolve("file"), OLD);
        assertArrayEquals(OLD, Files.readAllBytes(file)); // Legacy validation completed.
        Files.move(folder, temporary.resolve("moved"));
        Files.createSymbolicLink(folder, outside);
        Files.write(file, NEW, StandardOpenOption.TRUNCATE_EXISTING); // Legacy effect.
        assertArrayEquals(NEW, Files.readAllBytes(outside.resolve("file")));
    }

    @Test
    void privateRecordsAndLocksDoNotMutateHardLinkAliases() throws Exception {
        var file = temporary.resolve("record");
        var alias = temporary.resolve("outside");
        Files.write(file, OLD);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        Files.createLink(alias, file);
        SecureFiles.commitPrivate(file, OLD, NEW);
        assertArrayEquals(OLD, Files.readAllBytes(alias));
        assertEquals(
                PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(alias));
        assertEquals(
                PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        var lock = temporary.resolve("lock");
        Files.createLink(lock, alias);
        try (var channel = SecureFiles.lockChannel(lock);
                var lease = channel.lock()) {
            assertTrue(lease.isValid());
        }
        assertArrayEquals(OLD, Files.readAllBytes(alias));
        assertEquals(
                PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(alias));
    }

    @Test
    void symlinkedParentsDenyConfigurationCredentialAndLockAccess() throws Exception {
        var outside = Files.createDirectory(temporary.resolve("outside"));
        Files.write(outside.resolve("file"), OLD);
        var link = Files.createSymbolicLink(temporary.resolve("link"), outside);
        var file = link.resolve("file");
        assertThrows(IOException.class, () -> SecureFiles.read(file, 100));
        assertThrows(IOException.class, () -> SecureFiles.commitPrivate(file, OLD, NEW));
        assertThrows(IOException.class, () -> SecureFiles.lockChannel(file));
        assertThrows(IOException.class, () -> SecureFiles.commit(link.resolve("new"), null, NEW));
        assertArrayEquals(OLD, Files.readAllBytes(outside.resolve("file")));
        assertFalse(Files.exists(outside.resolve("new")));
    }

    private interface IO {
        void run() throws IOException;
    }

    private static void unchecked(IO action) {
        try {
            action.run();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
