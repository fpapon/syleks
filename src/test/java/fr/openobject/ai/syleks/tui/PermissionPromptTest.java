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

import fr.openobject.ai.syleks.model.ChatSnapshot;
import fr.openobject.ai.syleks.model.PermissionRequest;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PermissionPromptTest {
    private final PermissionRequest tool =
            new PermissionRequest(
                    1,
                    "Run bash command as build",
                    "Directory: /project\nTimeout: 60 seconds\nls -la",
                    true);

    @Test
    void cyclesOptionsAndResetsForEachRequest() {
        var prompt = new PermissionPrompt();
        prompt.sync(tool);
        assertEquals("once", prompt.choice());
        prompt.move(1);
        assertEquals("always", prompt.choice());
        prompt.sync(tool);
        assertEquals("always", prompt.choice());
        prompt.move(1);
        assertEquals("reject", prompt.choice());
        prompt.move(1);
        assertEquals("once", prompt.choice());
        prompt.move(-1);
        assertEquals("reject", prompt.choice());
        prompt.toggleExpanded();
        prompt.scroll(3);
        prompt.sync(new PermissionRequest(2, "Next", "details", true));
        assertEquals("once", prompt.choice());
        assertFalse(prompt.expanded());
        assertEquals(0, prompt.scroll());
    }

    @Test
    void nonToolRequestsOfferOnlyOnceAndReject() {
        var prompt = new PermissionPrompt();
        prompt.sync(new PermissionRequest(2, "Export conversation", "/project/session.md", false));
        prompt.move(1);
        assertEquals("reject", prompt.choice());
        prompt.move(-1);
        assertEquals("once", prompt.choice());
        var screen = render(prompt, 100, 30);
        assertTrue(text(screen).contains("Allow once"));
        assertTrue(text(screen).contains("Reject"));
        assertFalse(text(screen).contains("Always allow"));
    }

    @Test
    void rendersSelectedOptionScopeAndExpandedDetailsWithoutACursor() {
        var prompt = new PermissionPrompt();
        prompt.sync(tool);
        var screen = render(prompt, 100, 30);
        assertFalse(screen.cursorVisible());
        assertTrue(text(screen).contains("Permission required"));
        assertTrue(text(screen).contains("Allow once"));
        assertTrue(text(screen).contains("Always allow"));
        assertTrue(text(screen).contains("Enter confirm"));
        var row = row(screen.canvas(), 25);
        assertEquals(0xFDBA8C, screen.canvas().at(row.indexOf("Allow once"), 25).bg());
        prompt.move(1);
        screen = render(prompt, 100, 30);
        assertTrue(text(screen).contains("this session; deny rules still apply"));
        row = row(screen.canvas(), 25);
        assertEquals(0xFDBA8C, screen.canvas().at(row.indexOf("Always allow"), 25).bg());
        prompt.toggleExpanded();
        screen = render(prompt, 100, 30);
        assertTrue(text(screen).contains("ls -la"));
        assertFalse(screen.cursorVisible());
    }

    @Test
    void scrollsLongDetailsAndSupportsNarrowTerminals() {
        var prompt = new PermissionPrompt();
        prompt.sync(
                new PermissionRequest(
                        3, "Edit file", "first\n" + "middle\n".repeat(100) + "LAST DETAIL", true));
        prompt.scroll(1000);
        var screen = render(prompt, 100, 30);
        assertTrue(text(screen).contains("LAST DETAIL"));
        assertTrue(prompt.scroll() < 1000);
        prompt.scroll(-1000);
        assertEquals(0, prompt.scroll());
        screen = render(prompt, 35, 15);
        assertTrue(text(screen).contains("Once"));
        assertTrue(text(screen).contains("Always"));
        assertTrue(text(screen).contains("Reject"));
        assertFalse(screen.cursorVisible());
    }

    private ChatRenderer.Screen render(PermissionPrompt prompt, int width, int height) {
        return new ChatRenderer("/project")
                .render(
                        new ChatSnapshot(
                                List.of(),
                                "qwen3",
                                "Awaiting permission",
                                true,
                                "build",
                                "Local",
                                "Qwen3",
                                prompt.request()),
                        new Editor(),
                        width,
                        height,
                        0,
                        false,
                        prompt);
    }

    private String row(TerminalCanvas canvas, int y) {
        return IntStream.range(0, canvas.width())
                .mapToObj(x -> canvas.at(x, y).glyph())
                .collect(Collectors.joining());
    }

    private String text(ChatRenderer.Screen screen) {
        return IntStream.range(0, screen.canvas().height())
                .mapToObj(y -> row(screen.canvas(), y))
                .collect(Collectors.joining("\n"));
    }
}
