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
import fr.openobject.ai.syleks.json.SchemaValidator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Internal validation implementation. The v1 wire contract awaits human approval. */
final class EngineeringDocuments {
    static final int MAX_BYTES = 1024 * 1024;
    private static final JsonIO JSON = new JsonIO(32, 131072, 32);
    private static final SchemaValidator SCHEMA = schema();

    private EngineeringDocuments() {}

    static IOException error(String code) {
        return new IOException("Engineering: " + code);
    }

    private static SchemaValidator schema() {
        try (var in =
                EngineeringDocuments.class.getResourceAsStream(
                        "/engineering/contracts-v1.schema.json")) {
            if (in == null) throw new IllegalStateException("Missing Engineering contract schema");
            return new SchemaValidator(JSON.parse(in));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load Engineering contract schema", e);
        }
    }

    static JsonValue parse(byte[] bytes, String kind) throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException("Engineering: CANCELLED");
        if (bytes.length > MAX_BYTES) throw error("DOCUMENT_TOO_LARGE");
        final JsonValue value;
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            value = JSON.parse(text);
        } catch (IOException | RuntimeException e) {
            throw error("INVALID_DOCUMENT"); // Never include rejected input in diagnostics.
        }
        if (value == null || !value.isObject()) throw error("INVALID_DOCUMENT");
        if (!value.path("schema_version").isIntegralNumber()
                || value.path("schema_version").intValue() != 1) throw error("UNSUPPORTED_VERSION");
        if (!kind.equals(value.path("kind").asText()) || !SCHEMA.validate(value).isEmpty())
            throw error("INVALID_DOCUMENT");
        semantics(value);
        return value;
    }

    private static void semantics(JsonValue value) throws IOException {
        switch (value.path("kind").asText()) {
            case "project_registry" -> {
                unique(value.path("projects"), "id");
                unique(value.path("projects"), "root_path");
                for (var project : value.path("projects")) semantics(project);
                for (var route : value.path("local_routes"))
                    instant(route.path("reviewed_at").asText());
            }
            case "engineering_project" -> {
                absolute(value.path("root_path").asText());
                unique(value.path("technology_packs"), "id");
                for (var reference : value.path("technology_packs"))
                    absolute(reference.path("local_path").asText());
                semantics(value.path("token_budget"));
            }
            case "technology_pack" -> {
                unique(value.path("entries"), "path");
                for (var entry : value.path("entries")) relative(entry.path("path").asText());
            }
            case "workflow_definition" -> {
                Set<String> ids = unique(value.path("stages"), "id");
                var completed = new HashSet<String>();
                while (completed.size() < ids.size()) {
                    int before = completed.size();
                    for (var stage : value.path("stages")) {
                        var dependencies = new HashSet<String>();
                        for (var dependency : stage.path("depends_on"))
                            if (!ids.contains(dependency.asText())
                                    || !dependencies.add(dependency.asText()))
                                throw error("INVALID_DEPENDENCY");
                        if (completed.containsAll(dependencies))
                            completed.add(stage.path("id").asText());
                    }
                    if (before == completed.size()) throw error("CYCLIC_DEPENDENCY");
                }
            }
            case "workflow_run" -> unique(value.path("stages"), "id");
            case "token_budget" -> {
                long total = value.path("total_limit").longValue();
                if (value.path("input_limit").longValue() > total
                        || value.path("output_limit").longValue() > total
                        || value.path("reserved").longValue()
                                > total - value.path("consumed").longValue())
                    throw error("INVALID_BUDGET");
            }
            case "approval_gate" -> {
                boolean decided =
                        Set.of("APPROVED", "REJECTED").contains(value.path("status").asText());
                if (decided
                        && (value.path("reviewer").isNull() || value.path("decided_at").isNull()))
                    throw error("INVALID_DECISION");
                if (!decided
                        && (!value.path("reviewer").isNull() || !value.path("decided_at").isNull()))
                    throw error("INVALID_DECISION");
                if (!value.path("decided_at").isNull()) instant(value.path("decided_at").asText());
                if (!value.path("expires_at").isNull()) instant(value.path("expires_at").asText());
            }
            case "context_manifest" -> {
                unique(value.path("entries"), "path");
                for (var entry : value.path("entries")) relative(entry.path("path").asText());
            }
            case "repository_analysis" -> {
                unique(value.path("inputs"), "path");
                for (var entry : value.path("inputs")) relative(entry.path("path").asText());
                for (var finding : value.path("findings")) {
                    relative(finding.path("path").asText());
                    if (finding.path("end_line").longValue()
                            < finding.path("start_line").longValue())
                        throw error("INVALID_SOURCE_SPAN");
                }
            }
            default -> throw error("INVALID_DOCUMENT");
        }
    }

    private static Set<String> unique(JsonValue values, String key) throws IOException {
        var seen = new HashSet<String>();
        for (var entry : values)
            if (!seen.add(entry.path(key).asText())) throw error("DUPLICATE_IDENTITY");
        return seen;
    }

    private static void instant(String value) throws IOException {
        try {
            Instant.parse(value);
        } catch (RuntimeException e) {
            throw error("INVALID_TIMESTAMP");
        }
    }

    static Path relative(String value) throws IOException {
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException e) {
            throw error("UNTRUSTED_PATH");
        }
        if (path.isAbsolute()
                || !path.normalize().equals(path)
                || value.contains("\\")
                || value.contains(":")
                || value.endsWith("/")
                || value.contains("//")
                || value.equals(".")
                || value.equals("..")
                || value.startsWith("../")) throw error("UNTRUSTED_PATH");
        return path;
    }

    private static void absolute(String value) throws IOException {
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !path.normalize().equals(path)) throw error("UNTRUSTED_PATH");
        } catch (RuntimeException e) {
            throw error("UNTRUSTED_PATH");
        }
    }

    static byte[] read(Path path, int maximum) throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException("Engineering: CANCELLED");
        var bytes = fr.openobject.ai.syleks.fs.SecureFiles.read(path, maximum + 1);
        if (bytes.length > maximum) throw error("DOCUMENT_TOO_LARGE");
        return bytes;
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
