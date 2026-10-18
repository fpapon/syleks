package fr.openobject.ai.syleks.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Bounded UTF-8 configuration reads and project-scoped inheritance paths. */
public final class ConfigurationFiles {
    public static final int TEXT_LIMIT = 128 * 1024;
    public static final int INSTRUCTIONS_LIMIT = 256 * 1024;

    private ConfigurationFiles() {}

    public static String read(Path file, int limit) {
        if (!Files.isRegularFile(file))
            throw new IllegalStateException("Configuration is not a regular file: " + file);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit)
                throw new IllegalStateException(
                        "Configuration exceeds " + limit + " bytes: " + file);
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read UTF-8 configuration: " + file, e);
        }
    }

    /** Absence is optional; broken links, directories and unreadable files are configuration errors. */
    public static String optional(Path file, int limit) {
        return optional(file, limit, null);
    }

    public static String optional(Path file, int limit, Path boundary) {
        try {
            Files.readAttributes(
                    file,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return "";
        } catch (IOException e) {
            throw new IllegalStateException("Unable to inspect configuration: " + file, e);
        }
        try {
            if (boundary != null && !file.toRealPath().startsWith(boundary.toRealPath()))
                throw new IllegalStateException(
                        "Configuration resolves outside the project: " + file);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to resolve configuration: " + file, e);
        }
        return read(file, limit);
    }

    public static String instructions(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > INSTRUCTIONS_LIMIT)
            throw new IllegalStateException("Combined instructions exceed 256 KiB");
        return value;
    }

    public static List<Path> hierarchy(Path root, Path workspace) {
        root = fr.openobject.ai.syleks.session.ProjectService.canonical(root);
        workspace = fr.openobject.ai.syleks.session.ProjectService.canonical(workspace);
        if (!workspace.startsWith(root))
            throw new IllegalStateException("Workspace is outside its project root");
        var result = new ArrayList<Path>();
        result.add(root);
        Path current = root;
        for (Path name : root.relativize(workspace)) {
            if (name.toString().isEmpty()) continue;
            current = current.resolve(name);
            result.add(current);
        }
        return List.copyOf(result);
    }
}
