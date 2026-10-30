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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipboardTest {
    @TempDir Path root;

    @Test
    void nativeBuildSkipsAwtInitializationEntirely() {
        assertFalse(Clipboard.copyAwt("selected text", true));
    }

    @Test
    void selectsClipboardHelpersForTheDesktopSession() {
        assertEquals(
                List.of(List.of("wl-copy", "--type", "text/plain;charset=utf-8")),
                Clipboard.commands("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0")));
        assertEquals(
                List.of(
                        List.of("xclip", "-selection", "clipboard", "-in"),
                        List.of("xsel", "--clipboard", "--input")),
                Clipboard.commands("Linux", Map.of("DISPLAY", ":0")));
        assertTrue(Clipboard.commands("Linux", Map.of()).isEmpty());
        assertEquals(List.of(List.of("pbcopy")), Clipboard.commands("Mac OS X", Map.of()));
        assertEquals(List.of(List.of("clip.exe")), Clipboard.commands("Windows 11", Map.of()));
    }

    @Test
    void sendsExactUnicodeTextThroughStdinWithoutShellEvaluation() throws Exception {
        var output = root.resolve("clipboard.txt");
        var text = "Hello 世界\n'$(touch nope)' `echo nope`\n";
        assertTrue(
                Clipboard.copyCommand(
                        List.of(
                                "/bin/sh",
                                "-c",
                                "cat > \"$1\"",
                                "clipboard-test",
                                output.toString()),
                        text));
        assertEquals(text, Files.readString(output));
    }

    @Test
    void reportsFailedAndUnavailableHelpersAsFailures() {
        assertFalse(Clipboard.copyCommand(List.of("syleks-nonexistent-clipboard-helper"), "text"));
        assertFalse(
                Clipboard.copyCommand(List.of("/bin/sh", "-c", "cat >/dev/null; exit 1"), "text"));
    }
}
