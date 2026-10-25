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
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads pinned local reference data. Never clones, installs, executes, or grants permissions. */
final class TechnologyPackLoader {
    record Snapshot(
            String id, String version, String manifestSha256, Map<String, String> resources) {
        Snapshot {
            resources = Map.copyOf(resources);
        }
    }

    Snapshot load(JsonNode reference) throws IOException {
        Path root = Path.of(reference.path("local_path").asText()).toAbsolutePath().normalize();
        byte[] bytes =
                EngineeringDocuments.read(
                        root.resolve("pack.json"), EngineeringDocuments.MAX_BYTES);
        String digest = EngineeringDocuments.hash(bytes);
        if (!digest.equals(reference.path("manifest_sha256").asText()))
            throw EngineeringDocuments.error("HASH_MISMATCH");
        var manifest = EngineeringDocuments.parse(bytes, "technology_pack");
        if (!manifest.path("id").equals(reference.path("id"))
                || !manifest.path("version").equals(reference.path("version")))
            throw EngineeringDocuments.error("PACK_MISMATCH");
        var resources = new LinkedHashMap<String, String>();
        int total = 0;
        for (var entry : manifest.path("entries")) {
            String name = entry.path("path").asText();
            Path file = root.resolve(EngineeringDocuments.relative(name)).normalize();
            if (!file.startsWith(root)) throw EngineeringDocuments.error("UNTRUSTED_PATH");
            byte[] content = EngineeringDocuments.read(file, 128 * 1024);
            total += content.length;
            if (total > EngineeringDocuments.MAX_BYTES)
                throw EngineeringDocuments.error("DOCUMENT_TOO_LARGE");
            if (!EngineeringDocuments.hash(content).equals(entry.path("sha256").asText()))
                throw EngineeringDocuments.error("HASH_MISMATCH");
            if (entry.path("role").asText().equals("WORKFLOW"))
                EngineeringDocuments.parse(content, "workflow_definition");
            try {
                resources.put(
                        name,
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(content))
                                .toString());
            } catch (IOException e) {
                throw EngineeringDocuments.error("INVALID_DOCUMENT");
            }
        }
        return new Snapshot(
                manifest.path("id").asText(), manifest.path("version").asText(), digest, resources);
    }
}
