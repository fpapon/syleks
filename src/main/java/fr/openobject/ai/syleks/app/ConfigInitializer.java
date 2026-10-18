package fr.openobject.ai.syleks.app;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Generates editable defaults without loading existing configuration or opening the session database. */
@DefaultScoped
public class ConfigInitializer {
    public record Result(Path path, boolean created) {}

    public List<Result> initialize() throws IOException {
        return initialize(
                Path.of(System.getenv().getOrDefault("HOME", System.getProperty("user.home"))));
    }

    public List<Result> initialize(Path home) throws IOException {
        return initialize(home, null);
    }

    public List<Result> initialize(Path home, String configuration) throws IOException {
        var directory = home.resolve(".config/syleks").toAbsolutePath().normalize();
        boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
        FileAttribute<?>[] directoryAttributes =
                posix
                        ? new FileAttribute<?>[] {
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------"))
                        }
                        : new FileAttribute<?>[0];
        FileAttribute<?>[] fileAttributes =
                posix
                        ? new FileAttribute<?>[] {
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------"))
                        }
                        : new FileAttribute<?>[0];
        Files.createDirectories(directory.resolve("agents"), directoryAttributes);
        var results = new ArrayList<Result>();
        results.add(
                configuration == null
                        ? create(
                                "/defaults/agent-config.json",
                                directory.resolve("agent-config.json"),
                                fileAttributes)
                        : create(
                                configuration.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                directory.resolve("agent-config.json"),
                                fileAttributes));
        results.add(
                create(
                        "/agent-config.schema.json",
                        directory.resolve("agent-config.schema.json"),
                        fileAttributes));
        for (var name : List.of("build", "plan", "architect", "reviewer")) {
            results.add(
                    create(
                            "/defaults/agents/" + name + ".md",
                            directory.resolve("agents/" + name + ".md"),
                            fileAttributes));
        }
        return List.copyOf(results);
    }

    public static Path home() {
        return Path.of(System.getenv().getOrDefault("HOME", System.getProperty("user.home")));
    }

    /** Replace only the file version shown in the form. Keep schema and agent customizations intact. */
    public void save(Path home, ConfigInitWizard.Form form) throws IOException {
        String configuration = form.configuration();
        Path directory = home.resolve(".config/syleks").toAbsolutePath().normalize();
        boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
        FileAttribute<?>[] attributes =
                posix
                        ? new FileAttribute<?>[] {
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------"))
                        }
                        : new FileAttribute<?>[0];
        FileAttribute<?>[] directories =
                posix
                        ? new FileAttribute<?>[] {
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------"))
                        }
                        : new FileAttribute<?>[0];
        Files.createDirectories(directory, directories);
        Path target = directory.resolve("agent-config.json");
        Path lockPath = directory.resolve(".agent-config.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Unsafe configuration lock path");
        try (var channel =
                java.nio.channels.FileChannel.open(
                        lockPath,
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                        attributes)) {
            java.nio.channels.FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException busy) {
                throw new IOException("Another configuration save is in progress");
            }
            if (lock == null) throw new IOException("Another configuration save is in progress");
            try (lock) {
                boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (exists && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Configuration destination is not a regular file");
                String actual =
                        exists
                                ? Files.readString(target, java.nio.charset.StandardCharsets.UTF_8)
                                : null;
                if (!java.util.Objects.equals(actual, form.original()))
                    throw new IOException(
                            "Configuration changed on disk. Reopen /config to review the latest version.");
                // Complete missing auxiliary files before committing the configuration itself.
                Files.createDirectories(directory.resolve("agents"), directories);
                create(
                        "/agent-config.schema.json",
                        directory.resolve("agent-config.schema.json"),
                        attributes);
                for (String name : List.of("build", "plan", "architect", "reviewer")) {
                    create(
                            "/defaults/agents/" + name + ".md",
                            directory.resolve("agents/" + name + ".md"),
                            attributes);
                }
                Path temporary =
                        Files.createTempFile(directory, ".agent-config-", ".tmp", attributes);
                try {
                    Files.writeString(
                            temporary, configuration, java.nio.charset.StandardCharsets.UTF_8);
                    Files.move(
                            temporary,
                            target,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }

    private Result create(String resource, Path target, FileAttribute<?>[] attributes)
            throws IOException {
        final byte[] content;
        try (var input = ConfigInitializer.class.getResourceAsStream(resource)) {
            if (input == null)
                throw new IOException("Missing bundled configuration template: " + resource);
            content = input.readAllBytes();
        }
        return create(content, target, attributes);
    }

    private Result create(byte[] content, Path target, FileAttribute<?>[] attributes)
            throws IOException {
        try (var channel =
                Files.newByteChannel(
                        target,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        attributes)) {
            var buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) channel.write(buffer);
        } catch (FileAlreadyExistsException existing) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Configuration destination is not a regular file: " + target, existing);
            }
            return new Result(target, false);
        }
        return new Result(target, true);
    }
}
