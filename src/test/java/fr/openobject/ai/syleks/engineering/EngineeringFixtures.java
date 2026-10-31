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

import fr.openobject.ai.syleks.json.JsonIO;
import fr.openobject.ai.syleks.json.JsonValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class EngineeringFixtures {
    static final JsonIO JSON = new JsonIO();
    static final String HASH = "a".repeat(64);

    static JsonValue document(String kind) {
        var n = JSON.object().put("schema_version", 1).put("kind", kind);
        switch (kind) {
            case "token_budget" ->
                    n.put("id", "budget")
                            .put("input_limit", 800)
                            .put("output_limit", 200)
                            .put("total_limit", 1000)
                            .put("reserved", 0)
                            .put("consumed", 0)
                            .put("unknown_usage", "BLOCK");
            case "engineering_project" -> {
                n.put("id", "project")
                        .put("root_path", "/tmp/project")
                        .put("allow_cloud", false)
                        .put("provenance", "PRIVATE");
                n.putArray("technology_packs");
                n.set("token_budget", document("token_budget"));
            }
            case "technology_pack" -> {
                n.put("id", "test-pack").put("version", "1.0.0");
                n.putArray("entries");
            }
            case "workflow_definition" -> {
                n.put("id", "workflow").put("version", "1.0.0");
                var stage =
                        n.putArray("stages")
                                .addObject()
                                .put("id", "review")
                                .put("agent", "plan")
                                .putNull("approval_gate");
                stage.putArray("depends_on");
            }
            case "workflow_run" -> {
                n.put("id", "run")
                        .put("project_id", "project")
                        .put("workflow_id", "workflow")
                        .put("definition_sha256", HASH)
                        .putNull("session_id")
                        .put("revision", 0)
                        .put("status", "CREATED")
                        .put("context_sha256", HASH)
                        .put("budget_id", "budget");
                n.putArray("stages");
            }
            case "approval_gate" ->
                    n.put("id", "gate")
                            .put("run_id", "run")
                            .put("stage_id", "review")
                            .put("artifact_sha256", HASH)
                            .put("policy_sha256", HASH)
                            .put("action", "RELEASE")
                            .put("destination", "chatgpt/selected-model")
                            .put("status", "PENDING")
                            .putNull("reviewer")
                            .putNull("decided_at")
                            .putNull("expires_at");
            case "context_manifest" -> {
                n.put("id", "context").put("project_id", "project").put("revision", 0);
                n.putArray("entries");
            }
            case "repository_analysis" -> {
                n.put("id", "analysis")
                        .put("project_id", "project")
                        .put("working_tree_sha256", HASH)
                        .put("extractor_id", "test-extractor")
                        .put("extractor_version", "1.0.0");
                n.putArray("inputs");
                n.putArray("findings");
            }
            case "project_registry" -> {
                n.putArray("projects");
                n.putArray("local_routes");
            }
            default -> throw new IllegalArgumentException(kind);
        }
        return n;
    }

    static byte[] bytes(JsonValue n) {
        return n.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Path registry(Path root, String endpoint, boolean allowCloud) throws Exception {
        Files.createDirectories(root.resolve("project"));
        Path file = root.resolve("home/.config/syleks/engineering-projects.json");
        Files.createDirectories(file.getParent());
        var registry = document("project_registry");
        registry.withArray("projects")
                .add(
                        document("engineering_project")
                                .put("root_path", root.resolve("project").toString())
                                .put("allow_cloud", allowCloud));
        if (endpoint != null)
            registry.withArray("local_routes")
                    .addObject()
                    .put("provider", "ollama")
                    .put("endpoint", endpoint)
                    .put("reviewed_by", "synthetic-test-user")
                    .put("reviewed_at", "2026-10-25T12:00:00Z");
        Files.write(file, bytes(registry));
        return file;
    }
}
