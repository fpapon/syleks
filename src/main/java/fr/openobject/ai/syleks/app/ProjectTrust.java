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

import fr.openobject.ai.syleks.session.ProjectService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** User-owned content pins. Project files are read once and the reviewed snapshot is loaded. */
public final class ProjectTrust {
    private final Path workspace;
    private final Path record;
    private final Map<Path, String> files = new TreeMap<>();
    private final String digest;
    private final boolean trusted;

    public ProjectTrust(Path home, Path project) {
        workspace = ProjectService.canonical(project);
        record = recordPath(home, workspace);
        Path root = ProjectService.root(workspace);
        for (var directory : ConfigurationFiles.hierarchy(root, workspace)) {
            Path config = directory.resolve(".syleks/agent-config.json");
            String text = ConfigurationFiles.optional(config, 1024 * 1024, root);
            if (Files.exists(config, LinkOption.NOFOLLOW_LINKS)) files.put(config, text);
            Path agents = directory.resolve(".syleks/agents");
            if (!Files.exists(agents, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var entries = Files.list(agents)) {
                if (!agents.toRealPath().startsWith(root))
                    throw new IllegalStateException("Agent directory outside project: " + agents);
                for (var file :
                        entries.filter(p -> p.toString().endsWith(".md"))
                                .sorted()
                                .limit(129)
                                .toList()) {
                    if (files.size() >= 256)
                        throw new IllegalStateException("Too many project definitions");
                    files.put(
                            file,
                            ConfigurationFiles.optional(file, ConfigurationFiles.TEXT_LIMIT, root));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to inspect project agents", e);
            }
        }
        var manifest = new StringBuilder(workspace.toString()).append('\n');
        files.forEach(
                (path, text) -> manifest.append(path).append('\0').append(hash(text)).append('\n'));
        digest = hash(manifest.toString());
        trusted = ConfigurationFiles.optional(record, 1024).strip().equals(digest);
    }

    public String digest() {
        return digest;
    }

    public boolean trusted() {
        return trusted;
    }

    public String approvalRevision() {
        return hash(digest + "\0" + ConfigurationFiles.optional(record, 1024));
    }

    public String state() {
        return (trusted ? "trusted" : "untrusted or changed")
                + " workspace "
                + workspace
                + "; SHA-256 "
                + digest;
    }

    public Map<Path, String> files() {
        return Collections.unmodifiableMap(files);
    }

    public String content(Path path) {
        return files.getOrDefault(path, "");
    }

    /** Requires the exact digest obtained during the user's separate review step. */
    public void grant(String reviewedDigest) throws IOException {
        if (!digest.equals(reviewedDigest))
            throw new IllegalArgumentException(
                    "Project changed or digest does not match; inspect again");
        Files.createDirectories(record.getParent());
        Path temp = Files.createTempFile(record.getParent(), ".trust-", ".tmp");
        try {
            Files.writeString(temp, digest + "\n");
            Files.move(
                    temp,
                    record,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path recordPath(Path home, Path workspace) {
        return home.resolve(".config/syleks/project-trust")
                .resolve(hash(ProjectService.canonical(workspace).toString()));
    }

    /** Revocation must work even when changed project definitions are malformed or unreadable. */
    public static void revoke(Path home, Path workspace) throws IOException {
        Files.deleteIfExists(recordPath(home, workspace));
    }

    public void revoke() throws IOException {
        Files.deleteIfExists(record);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
