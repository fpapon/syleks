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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EngineeringDocumentsTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "engineering_project",
                "technology_pack",
                "workflow_definition",
                "workflow_run",
                "approval_gate",
                "context_manifest",
                "token_budget",
                "repository_analysis",
                "project_registry"
            })
    void parsesEveryContractAndRejectsUnknownFieldsAndFutureVersions(String kind) throws Exception {
        var n = EngineeringFixtures.document(kind);
        assertEquals(n, EngineeringDocuments.parse(EngineeringFixtures.bytes(n), kind));
        n.put("unexpected", "confidential-sentinel");
        var error =
                assertThrows(
                        IOException.class,
                        () -> EngineeringDocuments.parse(EngineeringFixtures.bytes(n), kind));
        assertFalse(error.getMessage().contains("confidential-sentinel"));
        n.remove("unexpected");
        n.put("schema_version", 2);
        assertTrue(
                assertThrows(
                                IOException.class,
                                () ->
                                        EngineeringDocuments.parse(
                                                EngineeringFixtures.bytes(n), kind))
                        .getMessage()
                        .contains("UNSUPPORTED_VERSION"));
    }

    @Test
    void rejectsAmbiguousMalformedUnboundedAndWrongKindDocuments() {
        for (String raw :
                new String[] {
                    "{}",
                    "[]",
                    "null",
                    "{\"schema_version\":1,\"schema_version\":2}",
                    EngineeringFixtures.document("token_budget") + " {}",
                    "[".repeat(40) + "]".repeat(40)
                })
            assertThrows(
                    IOException.class,
                    () ->
                            EngineeringDocuments.parse(
                                    raw.getBytes(StandardCharsets.UTF_8), "token_budget"));
        assertThrows(
                IOException.class,
                () -> EngineeringDocuments.parse(new byte[] {(byte) 0xff}, "token_budget"));
        assertThrows(
                IOException.class,
                () ->
                        EngineeringDocuments.parse(
                                new byte[EngineeringDocuments.MAX_BYTES + 1], "token_budget"));
        assertThrows(
                IOException.class,
                () ->
                        EngineeringDocuments.parse(
                                EngineeringFixtures.bytes(
                                        EngineeringFixtures.document("token_budget")),
                                "workflow_run"));
    }

    @Test
    void validatesBudgetsGraphsSpansAndDecisions() throws Exception {
        var budget =
                EngineeringFixtures.document("token_budget")
                        .put("consumed", 900)
                        .put("reserved", 101);
        assertThrows(
                IOException.class,
                () ->
                        EngineeringDocuments.parse(
                                EngineeringFixtures.bytes(budget), "token_budget"));
        var workflow = EngineeringFixtures.document("workflow_definition");
        ((com.fasterxml.jackson.databind.node.ArrayNode)
                        workflow.path("stages").get(0).path("depends_on"))
                .add("review");
        assertThrows(
                IOException.class,
                () ->
                        EngineeringDocuments.parse(
                                EngineeringFixtures.bytes(workflow), "workflow_definition"));
        var analysis = EngineeringFixtures.document("repository_analysis");
        analysis.withArray("findings")
                .addObject()
                .put("rule_id", "pattern")
                .put("path", "src/A.java")
                .put("start_line", 4)
                .put("end_line", 2)
                .put("provenance", "PRIVATE")
                .put("evidence_sha256", EngineeringFixtures.HASH);
        assertThrows(
                IOException.class,
                () ->
                        EngineeringDocuments.parse(
                                EngineeringFixtures.bytes(analysis), "repository_analysis"));
        var gate = EngineeringFixtures.document("approval_gate").put("status", "APPROVED");
        assertThrows(
                IOException.class,
                () -> EngineeringDocuments.parse(EngineeringFixtures.bytes(gate), "approval_gate"));
        gate.put("reviewer", "human").put("decided_at", "2026-10-25T12:00:00Z");
        assertDoesNotThrow(
                () -> EngineeringDocuments.parse(EngineeringFixtures.bytes(gate), "approval_gate"));
    }

    @Test
    void releaseReferenceNeverChangesPrivateProvenanceAndPathsStayRelative() throws Exception {
        var manifest = EngineeringFixtures.document("context_manifest");
        var entry =
                manifest.withArray("entries")
                        .addObject()
                        .put("path", "src/A.java")
                        .put("sha256", EngineeringFixtures.HASH)
                        .put("provenance", "PRIVATE")
                        .put("byte_count", 42)
                        .put("release_id", "reviewed-release");
        assertEquals(
                "PRIVATE",
                EngineeringDocuments.parse(EngineeringFixtures.bytes(manifest), "context_manifest")
                        .path("entries")
                        .get(0)
                        .path("provenance")
                        .asText());
        for (String path :
                new String[] {"..", "../escape", "/absolute", "a/../b", "C:\\private", "./file"}) {
            entry.put("path", path);
            assertThrows(
                    IOException.class,
                    () ->
                            EngineeringDocuments.parse(
                                    EngineeringFixtures.bytes(manifest), "context_manifest"),
                    path);
        }
    }
}
