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
package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBrowserTest {
    @TempDir Path root;

    @Test
    void navigatesFiltersPreviewsAndSelectsAcrossDirectories() throws Exception {
        Files.createDirectory(root.resolve("dir"));
        Files.writeString(root.resolve("dir/child.txt"), "child");
        Files.writeString(root.resolve("file.txt"), "hello");
        Files.writeString(root.resolve(".hidden"), "secret");
        var browser = new FileBrowser(new FileAttachments(root), "");
        assertEquals(
                List.of("dir", "file.txt"),
                browser.filtered().stream().map(e -> e.name().toString()).toList());
        browser.open();
        browser.toggle();
        browser.parent();
        browser.type('F');
        assertEquals(1, browser.filtered().size());
        browser.open();
        assertTrue(browser.previewing());
        assertTrue(browser.dismissPreview());
        browser.toggle();
        assertEquals(
                List.of(root.resolve("dir/child.txt"), root.resolve("file.txt")), browser.chosen());
        browser.backspace();
        browser.toggleHidden();
        assertEquals(3, browser.filtered().size());
        browser.sequence("F");
        browser.sequence("H");
        browser.parent();
        assertEquals(root, browser.directory());
        for (int[] size : new int[][] {{100, 30}, {30, 10}, {1, 1}})
            browser.paint(new TerminalCanvas(size[0], size[1]));
    }

    @Test
    void previewKeepsTheSelectedFileUntilDismissed() throws Exception {
        Files.writeString(root.resolve("a.txt"), "first");
        Files.writeString(root.resolve("b.txt"), "second");
        var browser = new FileBrowser(new FileAttachments(root), "");
        browser.move(1);
        browser.open();
        browser.type('a');
        browser.backspace();
        browser.toggleHidden();
        browser.sequence("H");
        assertEquals(List.of(root.resolve("b.txt")), browser.chosen());
        browser.dismissPreview();
        browser.type('a');
        assertEquals(List.of(root.resolve("a.txt")), browser.chosen());
    }

    @Test
    void snapshotsAreAtomicBoundedAndKeepMarkdownFencesIntact() throws Exception {
        var attachments = new FileAttachments(root);
        var file = Files.writeString(root.resolve("notes.txt"), "hello\n```\nworld");
        String draft = attachments.append("question", List.of(file));
        assertTrue(draft.startsWith("question\n\nAttached file"));
        assertTrue(draft.contains("\n````\nhello\n```\nworld\n````"));
        Files.writeString(file, "changed");
        assertTrue(draft.contains("world"));
        assertFalse(draft.contains("changed"));
        assertThrows(IOException.class, () -> attachments.append("a".repeat(12000), List.of(file)));
        Files.write(file, new byte[8001]);
        assertThrows(IOException.class, () -> attachments.read(file));
        Files.write(file, new byte[] {(byte) 0xff});
        assertThrows(IOException.class, () -> attachments.read(file));
        Files.writeString(file, "binary\u0000data");
        assertThrows(IOException.class, () -> attachments.read(file));
        assertThrows(IOException.class, () -> attachments.resolve("../outside"));
    }

    @Test
    void rejectsSymlinksAndSpecialFilesAndDoesNotBrowseOutsideWorkspace() throws Exception {
        var dir = Files.createDirectory(root.resolve("dir"));
        var file = Files.writeString(dir.resolve("file.txt"), "text");
        var link = Files.createSymbolicLink(root.resolve("link"), file);
        var linkedDir = Files.createSymbolicLink(root.resolve("linked-dir"), dir);
        var attachments = new FileAttachments(root);
        var browser = new FileBrowser(attachments, "");
        assertEquals(1, browser.filtered().size());
        assertThrows(IOException.class, () -> attachments.read(link));
        assertThrows(IOException.class, () -> attachments.read(linkedDir.resolve("file.txt")));
        assertThrows(IOException.class, () -> browser.navigate(linkedDir));
        assertEquals(root, browser.directory());
        assertThrows(IOException.class, () -> new FileBrowser(attachments, "../"));
    }
}
