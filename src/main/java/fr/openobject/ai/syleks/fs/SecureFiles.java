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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Descriptor-relative, no-follow file access. No weaker provider fallback is permitted. */
public final class SecureFiles {
    private SecureFiles() {}

    public static final int LIMIT = 1_000_000;

    public static Parent parent(Path file) throws IOException {
        return new Parent(file);
    }

    public static byte[] read(Path file, int limit) throws IOException {
        try (var parent = parent(file)) {
            return parent.read(limit);
        }
    }

    public record Entry(Path name, boolean directory, long size) {}

    /** Bounded metadata listing through the same no-follow directory traversal as reads. */
    public static List<Entry> list(Path directory, int limit) throws IOException {
        try (var parent = parent(directory.resolve(".syleks-list"))) {
            var entries = new ArrayList<Entry>();
            int visited = 0;
            for (var path : parent.directory) {
                if (++visited > limit)
                    throw new IOException("Directory exceeds " + limit + " entries");
                var name = path.getFileName();
                try {
                    var attributes =
                            parent.directory
                                    .getFileAttributeView(
                                            name,
                                            BasicFileAttributeView.class,
                                            LinkOption.NOFOLLOW_LINKS)
                                    .readAttributes();
                    if (attributes.isDirectory() || attributes.isRegularFile())
                        entries.add(new Entry(name, attributes.isDirectory(), attributes.size()));
                } catch (NoSuchFileException removed) {
                    // A file removed during listing is no longer a candidate.
                }
            }
            return List.copyOf(entries);
        }
    }

    public static String utf8(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return java.nio.charset.StandardCharsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    public static byte[] optional(Path file, int limit) throws IOException {
        try {
            return read(file, limit);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    public static void commit(Path file, byte[] before, byte[] after) throws IOException {
        try (var parent = parent(file)) {
            parent.commit(before, after, () -> {});
        }
    }

    public static void commitPrivate(Path file, byte[] before, byte[] after) throws IOException {
        try (var parent = parent(file)) {
            parent.privateReplacement = true;
            parent.commit(before, after, () -> {});
        }
    }

    public static FileChannel lockChannel(Path file) throws IOException {
        try (var parent = parent(file)) {
            var channel =
                    parent.directory.newByteChannel(
                            parent.name,
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS),
                            privateMode());
            if (channel instanceof FileChannel result) return result;
            channel.close();
            throw new IOException("File locks require a FileChannel-capable secure provider");
        }
    }

    public static void protect(Path path, boolean directory) throws IOException {
        try (var parent = parent(path)) {
            var view =
                    parent.directory.getFileAttributeView(
                            parent.name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException("Private storage requires POSIX permissions");
            var attributes = view.readAttributes();
            if (directory ? !attributes.isDirectory() : !attributes.isRegularFile())
                throw new IOException("Unexpected private storage entry");
            view.setPermissions(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
    }

    private static FileAttribute<?>[] privateMode() {
        return new FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        };
    }

    public static final class Parent implements AutoCloseable {
        private final List<DirectoryStream<Path>> handles = new ArrayList<>();
        private SecureDirectoryStream<Path> directory;
        private final Path name;
        private boolean privateReplacement;

        private Parent(Path file) throws IOException {
            var absolute = file.toAbsolutePath().normalize();
            name = absolute.getFileName();
            if (name == null) throw new IOException("Expected a file path");
            try {
                var root = Files.newDirectoryStream(absolute.getRoot());
                handles.add(root);
                if (!(root instanceof SecureDirectoryStream<Path> secure))
                    throw new IOException(
                            "Race-resistant file access requires SecureDirectoryStream");
                directory = secure;
                for (var part : absolute.getParent()) {
                    directory = directory.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
                    handles.add(directory);
                }
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }

        private BasicFileAttributes attributes(Path entry) throws IOException {
            var result =
                    directory
                            .getFileAttributeView(
                                    entry, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                            .readAttributes();
            if (!result.isRegularFile())
                throw new IOException("File is not a regular file (symlinks denied)");
            return result;
        }

        public byte[] read(int limit) throws IOException {
            var initial = attributes(name);
            try (var channel =
                            directory.newByteChannel(
                                    name,
                                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    var input = Channels.newInputStream(channel)) {
                var bytes = input.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IOException("File exceeds " + limit + " bytes");
                if (!same(initial, attributes(name)))
                    throw new IOException("File changed while opening");
                return bytes;
            }
        }

        private boolean same(BasicFileAttributes left, BasicFileAttributes right) {
            return left.fileKey() != null
                    && left.fileKey().equals(right.fileKey())
                    && left.size() == right.size()
                    && left.lastModifiedTime().equals(right.lastModifiedTime());
        }

        private byte[] optional() throws IOException {
            try {
                return read(LIMIT);
            } catch (NoSuchFileException absent) {
                return null;
            }
        }

        /** Hook is package-visible for deterministic entry/parent substitution tests. */
        @FunctionalInterface
        interface Move {
            void run(Path source, Path target) throws IOException;
        }

        void commit(byte[] before, byte[] after, Runnable beforeCheck) throws IOException {
            commit(
                    before,
                    after,
                    beforeCheck,
                    (source, target) -> directory.move(source, directory, target));
        }

        void commit(byte[] before, byte[] after, Runnable beforeCheck, Move move)
                throws IOException {
            if (Thread.currentThread().isInterrupted())
                throw new java.io.InterruptedIOException("File operation cancelled");
            var initial = before == null ? null : attributes(name);
            var actual = optional();
            if (before == null && actual != null)
                throw new FileAlreadyExistsException(name.toString());
            if (!Arrays.equals(before, actual))
                throw new IOException("File changed before commit; retry");
            if (before == null) {
                if (after == null) return;
                beforeCheck.run();
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("File operation cancelled");
                try (var channel =
                        directory.newByteChannel(
                                name,
                                Set.of(
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS),
                                privateMode())) {
                    write(channel, after);
                }
                return;
            }
            if (after == null) {
                beforeCheck.run();
                verify(initial, before);
                directory.deleteFile(name);
                return;
            }
            Path staged = name.getFileSystem().getPath(".syleks-" + UUID.randomUUID() + ".tmp");
            boolean created = false;
            try {
                try (var channel =
                        directory.newByteChannel(
                                staged,
                                Set.of(
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS),
                                privateMode())) {
                    created = true;
                    write(channel, after);
                    if (!(channel instanceof FileChannel file))
                        throw new IOException("Durable staging requires FileChannel");
                    file.force(true);
                }
                var original =
                        directory.getFileAttributeView(
                                name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                var replacement =
                        directory.getFileAttributeView(
                                staged, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (original == null || replacement == null)
                    throw new IOException(
                            "Permission-preserving replacement requires POSIX attributes");
                if (!privateReplacement)
                    replacement.setPermissions(original.readAttributes().permissions());
                beforeCheck.run();
                verify(initial, before);
                // SecureDirectoryStream.move has ATOMIC_MOVE semantics, with no copy fallback.
                move.run(staged, name);
                created = false;
            } finally {
                if (created) directory.deleteFile(staged);
            }
        }

        private void verify(BasicFileAttributes initial, byte[] before) throws IOException {
            if (Thread.currentThread().isInterrupted())
                throw new java.io.InterruptedIOException("File operation cancelled");
            if (!same(initial, attributes(name)) || !Arrays.equals(before, read(LIMIT)))
                throw new IOException("File changed during commit; retry");
        }

        private void write(java.nio.channels.SeekableByteChannel channel, byte[] value)
                throws IOException {
            var buffer = ByteBuffer.wrap(value);
            while (buffer.hasRemaining()) channel.write(buffer);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int i = handles.size() - 1; i >= 0; i--) {
                try {
                    handles.get(i).close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
