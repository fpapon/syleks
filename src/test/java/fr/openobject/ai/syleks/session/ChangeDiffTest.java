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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChangeDiffTest {
    private FileChangeJournal.Change change(byte[] before, byte[] after) {
        return new FileChangeJournal.Change(
                "id",
                "group",
                "session",
                "call",
                "src/Example.java",
                before,
                after,
                "APPLIED",
                "now");
    }

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void showsContextAndAccurateCoordinatesForSeparateEdits() {
        String before =
                java.util.stream.IntStream.rangeClosed(1, 30)
                        .mapToObj(i -> "line " + i + "\n")
                        .collect(java.util.stream.Collectors.joining());
        String after =
                before.replace("line 6\n", "replacement\n")
                        .replace("line 25\n", "extra\nline 25\n");
        String diff = ChangeDiff.render(List.of(change(bytes(before), bytes(after))));
        assertTrue(diff.contains("Modified · +2 -1"), diff);
        assertTrue(diff.contains("@@ -3,7 +3,7 @@"), diff);
        assertTrue(diff.contains("@@ -22,6 +22,7 @@"), diff);
        assertTrue(diff.contains("-line 6\n+replacement\n"), diff);
        assertFalse(diff.contains("line 15\n"), diff);
    }

    @Test
    void handlesCreationDeletionBinaryEmptyAndMissingFinalNewline() {
        assertTrue(
                ChangeDiff.render(List.of(change(null, bytes("new\n"))))
                        .contains("@@ -0,0 +1,1 @@\n+new\n"));
        assertTrue(
                ChangeDiff.render(List.of(change(bytes("old\n"), null)))
                        .contains("@@ -1,1 +0,0 @@\n-old\n"));
        assertTrue(
                ChangeDiff.render(List.of(change(bytes("x\n"), bytes("x"))))
                        .contains("\\ No newline at end of file"));
        assertTrue(
                ChangeDiff.render(List.of(change(new byte[] {0, 1}, bytes("text"))))
                        .contains("binary file: 2 → 4 bytes"));
        assertTrue(ChangeDiff.render(List.of(change(null, new byte[0]))).contains("Added · +0 -0"));
        assertEquals("No recorded native file changes", ChangeDiff.render(List.of()));
    }

    @Test
    void boundsLargeDiffsAndAvoidsQuadraticWork() {
        String diff =
                ChangeDiff.render(
                        List.of(change(bytes("old\n".repeat(4000)), bytes("new\n".repeat(4000)))));
        assertTrue(diff.endsWith("[Diff truncated]"));
        assertTrue(diff.length() < 32100);
        String giant = ChangeDiff.render(List.of(change(null, bytes("x".repeat(100000)))));
        assertTrue(giant.endsWith("[Diff truncated]"));
    }
}
