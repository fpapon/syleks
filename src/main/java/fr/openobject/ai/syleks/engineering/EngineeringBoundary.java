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
package fr.openobject.ai.syleks.engineering;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Internal cross-package enforcement facade; not a supported plugin API. */
public final class EngineeringBoundary {
    private static final Set<String> LOCAL_TOOLS =
            Set.of(
                    "read",
                    "write",
                    "edit",
                    "list",
                    "glob",
                    "grep",
                    "apply_patch",
                    "skill",
                    "todoread",
                    "todowrite",
                    "question",
                    "task");
    private final Path registryFile;
    private final String registryHash;
    private final boolean active;
    private final boolean allowCloud;
    private final List<JsonNode> routes;

    public EngineeringBoundary(Path home, Path workspace) {
        registryFile =
                home.resolve(".config/syleks/engineering-projects.json")
                        .toAbsolutePath()
                        .normalize();
        try {
            if (!present(registryFile)) {
                registryHash = null;
                active = false;
                allowCloud = false;
                routes = List.of();
                return;
            }
            byte[] bytes = EngineeringDocuments.read(registryFile, EngineeringDocuments.MAX_BYTES);
            var registry = EngineeringDocuments.parse(bytes, "project_registry");
            registryHash = EngineeringDocuments.hash(bytes);
            Path launch = workspace.toRealPath();
            boolean enrolled = false, cloud = true;
            for (var project : registry.path("projects")) {
                Path root = Path.of(project.path("root_path").asText()).toRealPath();
                if (registryFile.startsWith(root))
                    throw EngineeringDocuments.error("UNTRUSTED_REGISTRY");
                if (launch.startsWith(root)) {
                    enrolled = true;
                    cloud &= project.path("allow_cloud").booleanValue();
                    for (var reference : project.path("technology_packs"))
                        new TechnologyPackLoader().load(reference);
                }
            }
            active = enrolled;
            allowCloud = cloud;
            var local = new ArrayList<JsonNode>();
            registry.path("local_routes").forEach(local::add);
            routes = List.copyOf(local);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Engineering: INVALID_REGISTRY");
        }
    }

    public boolean active() {
        return active;
    }

    private void unchanged() throws IOException {
        boolean exists = present(registryFile);
        if (registryHash == null && !exists) return;
        if (registryHash == null
                || !exists
                || !registryHash.equals(
                        EngineeringDocuments.hash(
                                EngineeringDocuments.read(
                                        registryFile, EngineeringDocuments.MAX_BYTES))))
            throw EngineeringDocuments.error("CONFIGURATION_CHANGED_RESTART_REQUIRED");
    }

    public void checkInference(String provider, URI endpoint, URI proxy) throws IOException {
        unchanged();
        if (!active) return;
        if (Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException("Engineering: CANCELLED");
        // P01 has no trusted exact-payload release executor. OAuth/API keys cannot bypass this
        // gate.
        if (Set.of("chatgpt", "openai-api", "spacexsi", "openrouter", "opencode", "google")
                .contains(provider))
            throw EngineeringDocuments.error(
                    !allowCloud
                            ? "CLOUD_DISABLED"
                            : provider.equals("chatgpt")
                                    ? "AUTHORIZED_RELEASE_REQUIRED"
                                    : "OFFICIAL_SUBSCRIPTION_REQUIRED");
        if (proxy != null
                || endpoint.getHost() == null
                || endpoint.getScheme() == null
                || !Set.of("127.0.0.1", "[::1]").contains(endpoint.getHost())
                || !Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null)
            throw EngineeringDocuments.error("UNVERIFIED_LOCAL_ROUTE");
        for (var route : routes)
            if (provider.equals(route.path("provider").asText())
                    && endpoint.toString().equals(route.path("endpoint").asText())) return;
        throw EngineeringDocuments.error("UNVERIFIED_LOCAL_ROUTE");
    }

    private static boolean present(Path file) throws IOException {
        try {
            Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException missing) {
            // Some filesystems report ENOTDIR as "missing". Only a missing child of an
            // accessible directory is optional; a file/invalid ancestor must fail closed.
            for (Path parent = file.getParent(); parent != null; parent = parent.getParent()) {
                try {
                    var attributes =
                            Files.readAttributes(
                                    parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isDirectory() && !Files.isDirectory(parent))
                        throw EngineeringDocuments.error("UNTRUSTED_PATH");
                    return false;
                } catch (NoSuchFileException absentParent) {
                    // Inspect the nearest existing ancestor before accepting absence.
                }
            }
            return false;
        }
    }

    public void checkTool(String name) throws IOException {
        unchanged();
        if (active && !LOCAL_TOOLS.contains(name))
            throw EngineeringDocuments.error("EXTERNAL_TOOL_BLOCKED");
    }
}
