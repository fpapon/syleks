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

import static fr.openobject.ai.syleks.tui.TerminalCanvas.*;

import java.util.ArrayList;
import java.util.List;

/** Scrollable reference owned by the terminal thread. */
final class HelpModal {
    private record Entry(String command, String description) {}

    private record Line(String text, int color, boolean bold) {}

    private static final List<Entry> ENTRIES =
            List.of(
                    new Entry("Chat & sessions", ""),
                    new Entry("/new · /clear", "Start a new chat; keep the previous session."),
                    new Entry(
                            "/sessions",
                            "Filter saved sessions by title, ID or model; PgUp/PgDn and Home/End navigate."),
                    new Entry("/session ID", "Open a saved session by ID."),
                    new Entry("/children · /parent", "Browse child tasks or return to the parent."),
                    new Entry("/resume", "Open the latest session."),
                    new Entry("/rename TITLE", "Rename this session."),
                    new Entry(
                            "/fork [message-id]",
                            "Copy this conversation, optionally through a selected message."),
                    new Entry("/messages", "Show message IDs for a fork boundary."),
                    new Entry("/compact", "Generate and save a summary; keep the full transcript."),
                    new Entry("/import PATH", "Import a validated JSON session archive."),
                    new Entry("/changes, /diff", "Inspect recorded native file changes."),
                    new Entry(
                            "/undo, /redo",
                            "Restore recorded files after checking for external edits."),
                    new Entry("Models & accounts", ""),
                    new Entry("/connect", "Connect a provider or account."),
                    new Entry("/providers [ID]", "Choose a provider."),
                    new Entry("/models", "Browse and select a model."),
                    new Entry("/model MODEL", "Select a provider/model or an Ollama model ID."),
                    new Entry("/accounts", "Manage connected accounts."),
                    new Entry("/logout [ACCOUNT]", "Sign out of the active or named account."),
                    new Entry("Agents", ""),
                    new Entry("/agents · /agent", "List available agents and their modes."),
                    new Entry("/agent NAME", "Switch to a primary agent."),
                    new Entry("/subagent NAME TASK", "Ask a subagent to handle a task."),
                    new Entry("@NAME TASK", "Another way to call a subagent."),
                    new Entry("Permissions & exports", ""),
                    new Entry(
                            "/permissions [NAME]",
                            "Show permissions for the active or named agent."),
                    new Entry(
                            "/approve [once|always]",
                            "Allow a pending action once or for this session."),
                    new Entry("/deny", "Reject the pending action."),
                    new Entry(
                            "/answer TEXT", "Answer a tool question; option numbers are accepted."),
                    new Entry("/save PATH", "Export displayed chat as Markdown; never overwrite."),
                    new Entry("Settings & help", ""),
                    new Entry("/config [init|edit]", "Open the configuration form."),
                    new Entry("/restart", "Reload settings; keep this conversation and draft."),
                    new Entry(
                            "/sidebar [auto|show|hide]",
                            "Toggle the token sidebar; saved preference."),
                    new Entry(
                            "/usage",
                            "Inspect token totals, configured input/output budgets and request timings."),
                    new Entry("/help", "Open this reference."),
                    new Entry("/demo", "Preview tool cards and code highlighting."),
                    new Entry("/quit · /exit", "Close Syleks."),
                    new Entry("Keyboard shortcuts", ""),
                    new Entry("Enter", "Send a message or run a command."),
                    new Entry("Ctrl+O", "Insert a newline."),
                    new Entry("←/→ · Backspace · Delete", "Move the cursor or edit your draft."),
                    new Entry("↑/↓", "Recall older/newer prompts; Down restores your draft."),
                    new Entry("PgUp/PgDn", "Scroll the conversation."),
                    new Entry("Tab", "Cycle primary agents while idle."),
                    new Entry("Ctrl+P", "Open or close this help popup; keep your draft."),
                    new Entry("Ctrl+G", "Open configuration; keep your draft."),
                    new Entry("Ctrl+R", "Browse sessions; keep your draft."),
                    new Entry("Esc", "Dismiss a popup or cancel active work."),
                    new Entry("Ctrl+C", "Cancel generation or approval; exit when idle."),
                    new Entry("Ctrl+D", "Exit Syleks."),
                    new Entry("Approval controls", ""),
                    new Entry("Tab · ←/→", "Choose Once, Always or Reject."),
                    new Entry("Enter", "Confirm the selected approval action."),
                    new Entry("Ctrl+F", "Expand or collapse the approval details."),
                    new Entry("↑/↓ · PgUp/PgDn", "Scroll approval details."));

    private int scroll;
    private int visible = 1;
    private int lastScroll;

    void reset() {
        scroll = 0;
    }

    void sequence(String sequence) {
        switch (sequence) {
            case "A" -> scroll = Math.max(0, scroll - 1);
            case "B" -> scroll = Math.min(lastScroll, scroll + 1);
            case "5~" -> scroll = Math.max(0, scroll - visible);
            case "6~" -> scroll = Math.min(lastScroll, scroll + visible);
            case "H", "1~", "7~" -> scroll = 0;
            case "F", "4~", "8~" -> scroll = lastScroll;
            default -> {}
        }
    }

    ChatRenderer.Screen paint(TerminalCanvas canvas) {
        if (canvas.width() < 35 || canvas.height() < 15) {
            canvas.fill(0, 0, canvas.width(), canvas.height(), BG);
            canvas.text(
                    0,
                    0,
                    ChatRenderer.fit("Resize terminal (min 35 × 15)", canvas.width()),
                    YELLOW,
                    BG,
                    false);
            return new ChatRenderer.Screen(canvas, 0, 0, false);
        }
        int width = Math.min(100, canvas.width() - 4);
        int height = Math.min(30, canvas.height() - 2);
        int left = (canvas.width() - width) / 2;
        int top = (canvas.height() - height) / 2;
        canvas.fill(left, top, width, height, INPUT);
        canvas.text(left, top, "╭" + "─".repeat(width - 2) + "╮", BLUE, INPUT, false);
        for (int row = 1; row < height - 1; row++) {
            canvas.text(left, top + row, "│", BORDER, INPUT, false);
            canvas.text(left + width - 1, top + row, "│", BORDER, INPUT, false);
        }
        canvas.text(left, top + height - 1, "╰" + "─".repeat(width - 2) + "╯", BLUE, INPUT, false);
        line(canvas, left, top + 1, width, "Syleks · Help", BLUE, true);
        line(
                canvas,
                left,
                top + 2,
                width,
                width < 65
                        ? "CAPS=value · []=optional"
                        : "Type a command in chat · CAPS = your value · [brackets] = optional",
                SUB,
                false);
        var lines = lines(width - 6);
        visible = height - 7;
        lastScroll = Math.max(0, lines.size() - visible);
        scroll = Math.clamp(scroll, 0, lastScroll);
        for (int row = 0; row < visible && scroll + row < lines.size(); row++) {
            var value = lines.get(scroll + row);
            line(canvas, left, top + 4 + row, width, value.text(), value.color(), value.bold());
        }
        line(
                canvas,
                left,
                top + height - 3,
                width,
                (scroll + 1)
                        + "–"
                        + Math.min(scroll + visible, lines.size())
                        + " / "
                        + lines.size()
                        + " · "
                        + (width < 45
                                ? (scroll < lastScroll ? "More" : "End")
                                : (scroll < lastScroll ? "More below" : "End of help")),
                SUB,
                false);
        line(
                canvas,
                left,
                top + height - 2,
                width,
                width < 45
                        ? "↑↓ Scroll · Esc Close"
                        : width < 65
                                ? "↑↓ Scroll · Esc/Enter Close"
                                : "↑↓ Scroll · PgUp/PgDn Page · Home/End · Esc/Enter Close",
                SUB,
                false);
        return new ChatRenderer.Screen(canvas, 0, 0, false);
    }

    private List<Line> lines(int width) {
        var lines = new ArrayList<Line>();
        for (var entry : ENTRIES) {
            if (entry.description().isEmpty()) {
                if (!lines.isEmpty()) lines.add(new Line("", WHITE, false));
                lines.add(new Line(entry.command(), BLUE, true));
            } else if (width >= 65) {
                var description = ChatRenderer.wrapProse(entry.description(), width - 28);
                String label =
                        entry.command() + " ".repeat(28 - ChatRenderer.textWidth(entry.command()));
                for (int row = 0; row < description.size(); row++) {
                    String prefix = row == 0 ? label : " ".repeat(28);
                    lines.add(new Line(prefix + description.get(row), WHITE, false));
                }
            } else {
                lines.add(new Line(entry.command(), GREEN, false));
                for (var description : ChatRenderer.wrapProse(entry.description(), width - 2))
                    lines.add(new Line("  " + description, SUB, false));
            }
        }
        return lines;
    }

    private void line(
            TerminalCanvas canvas,
            int left,
            int top,
            int width,
            String value,
            int color,
            boolean bold) {
        canvas.text(left + 3, top, ChatRenderer.fit(value, width - 6), color, INPUT, bold);
    }
}
