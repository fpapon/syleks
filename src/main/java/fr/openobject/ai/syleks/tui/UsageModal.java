package fr.openobject.ai.syleks.tui;

import static fr.openobject.ai.syleks.tui.TerminalCanvas.*;

import fr.openobject.ai.syleks.usage.*;
import java.util.*;

final class UsageModal {
    private int scroll, max;

    void move(int amount) {
        scroll = Math.clamp(scroll + amount, 0, max);
    }

    ChatRenderer.Screen paint(
            TerminalCanvas canvas,
            TokenUsageAggregator.Snapshot snapshot,
            String provider,
            String model) {
        int width = Math.max(1, Math.min(72, canvas.width() - 4)),
                height = Math.max(1, canvas.height() - 4);
        int x = (canvas.width() - width) / 2, y = (canvas.height() - height) / 2;
        var lines =
                new ArrayList<>(
                        TokenUsageSidebar.lines(snapshot, provider, model, Math.max(1, width - 4)));
        lines.add("");
        lines.add("BY PROVIDER (total tokens)");
        snapshot.providers().forEach((k, v) -> lines.add(k + ": " + TokenUsageSidebar.counter(v)));
        lines.add("BY MODEL (total tokens)");
        snapshot.models().forEach((k, v) -> lines.add(k + ": " + TokenUsageSidebar.counter(v)));
        max = Math.max(0, lines.size() - Math.max(1, height - 3));
        scroll = Math.min(scroll, max);
        canvas.fill(x, y, width, height, INPUT);
        for (int i = scroll; i < Math.min(lines.size(), scroll + height - 3); i++)
            canvas.text(
                    x + 1,
                    y + 1 + i - scroll,
                    ChatRenderer.fit(lines.get(i), Math.max(0, width - 2)),
                    SUB,
                    INPUT,
                    false);
        canvas.text(
                x + 1,
                y + height - 1,
                ChatRenderer.fit("↑/↓ PgUp/PgDn scroll · Esc close", Math.max(0, width - 2)),
                BLUE,
                INPUT,
                false);
        return new ChatRenderer.Screen(canvas, 0, 0, false);
    }
}
