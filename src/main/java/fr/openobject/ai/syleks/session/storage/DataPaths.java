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
package fr.openobject.ai.syleks.session.storage;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

@DefaultScoped
public class DataPaths {
    private Path directory;

    public DataPaths() {}

    private void initialize(Path home, Map<String, String> environment) {
        String override = environment.get("SYLEKS_DATA_DIR");
        String xdg = environment.get("XDG_DATA_HOME");
        directory =
                (override != null && !override.isBlank()
                                ? Path.of(override)
                                : xdg != null && !xdg.isBlank()
                                        ? Path.of(xdg).resolve("syleks")
                                        : home.resolve(".local/share/syleks"))
                        .toAbsolutePath()
                        .normalize();
        try {
            if (Files.isSymbolicLink(directory))
                throw new IOException("Data directory must not be a symbolic link");
            Files.createDirectories(directory);
            directory = directory.toRealPath();
            // Refuse accidental storage inside a working tree (including Git worktrees).
            for (Path parent = directory; parent != null; parent = parent.getParent()) {
                if (Files.isRegularFile(parent.resolve(".git"))
                        || Files.isRegularFile(parent.resolve(".git/HEAD")))
                    throw new IOException(
                            "Data directory is inside a Git working tree: " + directory);
            }
            protect(directory, true);
            for (String child : new String[] {"attachments", "tool-outputs", "backups", "locks"}) {
                var path = directory.resolve(child);
                if (Files.isSymbolicLink(path))
                    throw new IOException("Unsafe data directory: " + path);
                Files.createDirectories(path);
                protect(path, true);
            }
        } catch (IOException e) {
            directory = null;
            throw new IllegalStateException(
                    "Unable to initialize Syleks data directory: " + e.getMessage(), e);
        }
    }

    public static DataPaths configured(Path home, Map<String, String> environment) {
        var paths = new DataPaths();
        paths.initialize(home, environment);
        return paths;
    }

    public synchronized Path directory() {
        if (directory == null)
            initialize(Path.of(System.getProperty("user.home")), System.getenv());
        return directory;
    }

    public Path database() {
        return directory().resolve("syleks.db");
    }

    public static void protect(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException ignored) {
            /* Non-POSIX filesystem uses its own ACLs. */
        }
    }
}
