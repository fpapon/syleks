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

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.session.model.Project;
import fr.openobject.ai.syleks.session.repository.ProjectRepository;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@DefaultScoped
public class ProjectService {
    public java.nio.file.Path workspace() {
        return workspace;
    }

    private final Project project;
    private final Path workspace;

    public ProjectService(AppConfig config, ProjectRepository projects) {
        this.workspace = config.workspace();
        this.project = identify(config.workspace(), projects);
    }

    public static Project identify(Path directory, ProjectRepository projects) {
        Path root = root(directory);
        String remote = git(root, "config", "--get", "remote.origin.url");
        // Do not persist credentials embedded in a remote URL.
        if (remote != null)
            remote = remote.replaceAll("(https?://)[^/@]+@", "$1").replaceAll("[?#].*$", "");
        return projects.upsertProject(
                root.toString(),
                remote,
                root.getFileName() == null ? root.toString() : root.getFileName().toString());
    }

    public static Path root(Path directory) {
        Path root = canonical(directory);
        String gitRoot = git(root, "rev-parse", "--show-toplevel");
        if (gitRoot != null) root = canonical(Path.of(gitRoot));
        return root;
    }

    public static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String git(Path root, String... arguments) {
        Process process = null;
        try {
            var command = new java.util.ArrayList<String>();
            command.add("git");
            command.add("-C");
            command.add(root.toString());
            command.addAll(java.util.List.of(arguments));
            process =
                    new ProcessBuilder(command)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) return null;
            if (process.exitValue() != 0) return null;
            String value =
                    new String(
                                    process.getInputStream().readNBytes(16384),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .strip();
            return value.isEmpty() ? null : value;
        } catch (java.io.IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null) {
                process.destroyForcibly();
                try {
                    process.getInputStream().close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }

    public Project current() {
        return project;
    }

    public String currentId() {
        return project.id();
    }
}
