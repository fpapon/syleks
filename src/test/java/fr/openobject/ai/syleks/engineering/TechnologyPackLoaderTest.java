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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TechnologyPackLoaderTest {
    @TempDir Path root;

    @Test
    void loadsPinnedLocalTextAndRejectsManifestResourceAndVersionTampering() throws Exception {
        Files.writeString(
                root.resolve("reference.md"), "Synthetic fixture reference; never execute.");
        var manifest = EngineeringFixtures.document("technology_pack");
        manifest.withArray("entries")
                .addObject()
                .put("path", "reference.md")
                .put("role", "SKILL")
                .put(
                        "sha256",
                        EngineeringDocuments.hash(
                                Files.readAllBytes(root.resolve("reference.md"))));
        Files.write(root.resolve("pack.json"), EngineeringFixtures.bytes(manifest));
        var ref =
                EngineeringFixtures.JSON
                        .createObjectNode()
                        .put("id", "test-pack")
                        .put("version", "1.0.0")
                        .put("local_path", root.toString())
                        .put(
                                "manifest_sha256",
                                EngineeringDocuments.hash(
                                        Files.readAllBytes(root.resolve("pack.json"))));
        var loader = new TechnologyPackLoader();
        var snapshot = loader.load(ref);
        assertTrue(snapshot.resources().get("reference.md").contains("Synthetic fixture"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.resources().clear());
        ref.put("version", "2.0.0");
        assertThrows(IOException.class, () -> loader.load(ref));
        ref.put("version", "1.0.0");
        Files.writeString(root.resolve("reference.md"), "changed");
        assertThrows(IOException.class, () -> loader.load(ref));
        Files.writeString(root.resolve("pack.json"), "{}");
        assertThrows(IOException.class, () -> loader.load(ref));
    }

    @Test
    void refusesLinksAndCancellationWithoutLoadingContent() throws Exception {
        Path outside = Files.writeString(root.resolve("outside"), "sentinel");
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, outside);
        assertThrows(IOException.class, () -> EngineeringDocuments.read(link, 1024));
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    java.io.InterruptedIOException.class,
                    () -> EngineeringDocuments.read(outside, 1024));
        } finally {
            assertTrue(Thread.interrupted());
        }
    }
}
