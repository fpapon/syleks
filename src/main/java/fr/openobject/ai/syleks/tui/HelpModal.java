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

import fr.openobject.ai.syleks.i18n.Messages;
import java.util.ArrayList;
import java.util.List;

/** Scrollable reference owned by the terminal thread. */
final class HelpModal {
    private record Entry(String command, String description) {}

    private record Line(String text, int color, boolean bold) {}

    private static final List<Entry> ENTRIES =
            List.of(
                    new Entry("help.section.sessions", ""),
                    new Entry("/new · /clear", "help.new"),
                    new Entry("/sessions", "help.sessions"),
                    new Entry("/session ID", "help.session"),
                    new Entry("/children · /parent", "help.children"),
                    new Entry("/resume", "help.resume"),
                    new Entry("/rename TITLE", "help.rename"),
                    new Entry("/fork [message-id]", "help.fork"),
                    new Entry("/messages", "help.messages"),
                    new Entry("/compact", "help.compact"),
                    new Entry("/import PATH", "help.import"),
                    new Entry("/changes", "help.changes"),
                    new Entry("/diff", "help.diff"),
                    new Entry("/undo, /redo", "help.undo"),
                    new Entry("help.section.files", ""),
                    new Entry("/files [DIRECTORY] · Ctrl+F", "help.files"),
                    new Entry("/attach [PATH]", "help.attach"),
                    new Entry("File browser", "help.browser"),
                    new Entry("help.section.models", ""),
                    new Entry("/connect", "help.connect"),
                    new Entry("/providers [ID]", "help.providers"),
                    new Entry("/models", "help.models"),
                    new Entry("/model MODEL", "help.model"),
                    new Entry("/accounts", "help.accounts"),
                    new Entry("/logout [ACCOUNT]", "help.logout"),
                    new Entry("help.section.agents", ""),
                    new Entry("/agents · /agent", "help.agents"),
                    new Entry("/agent NAME", "help.agent"),
                    new Entry("/subagent NAME TASK", "help.subagent"),
                    new Entry("@NAME TASK", "help.at"),
                    new Entry("help.section.permissions", ""),
                    new Entry("/permissions [NAME]", "help.permissions"),
                    new Entry("/approve [once|remember]", "help.approve"),
                    new Entry("/approvals [revoke ID|all]", "help.approvals"),
                    new Entry("/deny", "help.deny"),
                    new Entry("/answer TEXT", "help.answer"),
                    new Entry("/save PATH", "help.save"),
                    new Entry("help.section.settings", ""),
                    new Entry("/config [init|edit]", "help.config"),
                    new Entry("/restart", "help.restart"),
                    new Entry("/sidebar [auto|show|hide]", "help.sidebar"),
                    new Entry("/usage", "help.usage"),
                    new Entry("/help", "help.help"),
                    new Entry("/demo", "help.demo"),
                    new Entry("/quit · /exit", "help.quit"),
                    new Entry("/history [clear|on|off]", "help.history"),
                    new Entry("Leading space", "help.leading_space"),
                    new Entry("help.section.keys", ""),
                    new Entry("Enter", "help.key.enter"),
                    new Entry("Ctrl+O", "help.key.newline"),
                    new Entry("←/→ · Backspace · Delete", "help.key.edit"),
                    new Entry("↑/↓", "help.key.recall"),
                    new Entry("Ctrl+X", "help.key.clear_recall"),
                    new Entry("PgUp/PgDn", "help.key.page"),
                    new Entry("Mouse wheel · Shift/Ctrl+↑/↓", "help.key.wheel"),
                    new Entry("Mouse drag", "help.key.drag"),
                    new Entry("Ctrl+Home/End", "help.key.jump"),
                    new Entry("Tab", "help.key.tab"),
                    new Entry("Ctrl+P", "help.key.help"),
                    new Entry("Ctrl+G", "help.key.config"),
                    new Entry("Ctrl+R", "help.key.sessions"),
                    new Entry("Esc", "help.key.esc"),
                    new Entry("Ctrl+C", "help.key.interrupt"),
                    new Entry("Ctrl+D", "help.key.exit"),
                    new Entry("help.section.approval", ""),
                    new Entry("Tab · ←/→", "help.key.choose"),
                    new Entry("Enter", "help.key.confirm"),
                    new Entry("Ctrl+F", "help.key.expand"),
                    new Entry("↑/↓ · PgUp/PgDn", "help.key.details"));

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
                    ChatRenderer.fit(Messages.text("help.resize"), canvas.width()),
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
        line(canvas, left, top + 1, width, Messages.text("help.title"), BLUE, true);
        line(
                canvas,
                left,
                top + 2,
                width,
                Messages.text(width < 65 ? "help.hint.short" : "help.hint"),
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
                                ? Messages.text(
                                        scroll < lastScroll ? "help.more.short" : "help.end.short")
                                : Messages.text(scroll < lastScroll ? "help.more" : "help.end")),
                SUB,
                false);
        line(
                canvas,
                left,
                top + height - 2,
                width,
                Messages.text(
                        width < 45
                                ? "help.keys.short"
                                : width < 65 ? "help.keys.mid" : "help.keys"),
                SUB,
                false);
        return new ChatRenderer.Screen(canvas, 0, 0, false);
    }

    private List<Line> lines(int width) {
        var lines = new ArrayList<Line>();
        for (var source : ENTRIES) {
            var entry =
                    source.description().isEmpty()
                            ? new Entry(Messages.text(source.command()), "")
                            : new Entry(source.command(), Messages.text(source.description()));
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
