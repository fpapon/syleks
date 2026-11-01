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
import fr.openobject.ai.syleks.usage.*;
import java.util.*;

final class UsageModal {
    private int scroll, max, visible = 8;

    void move(int amount) {
        scroll = Math.clamp(scroll + amount, 0, max);
    }

    void page(int direction) {
        move(direction * visible);
    }

    void edge(boolean end) {
        scroll = end ? max : 0;
    }

    ChatRenderer.Screen paint(
            TerminalCanvas canvas,
            TokenUsageAggregator.Snapshot snapshot,
            String provider,
            String model) {
        return paint(canvas, snapshot, provider, model, null);
    }

    ChatRenderer.Screen paint(
            TerminalCanvas canvas,
            TokenUsageAggregator.Snapshot snapshot,
            String provider,
            String model,
            ContextMetricsService.Limits limits) {
        int width = Math.max(1, Math.min(72, canvas.width() - 4)),
                height = Math.max(1, canvas.height() - 4);
        int x = (canvas.width() - width) / 2, y = (canvas.height() - height) / 2;
        var lines =
                new ArrayList<>(
                        TokenUsageSidebar.lines(snapshot, provider, model, Math.max(1, width - 4)));
        lines.add("");
        if (limits != null) {
            lines.add(Messages.text("usage.budget"));
            lines.add(Messages.text("usage.window", Long.toString(limits.window())));
            lines.add(Messages.text("usage.reserve", Long.toString(limits.responseReserve())));
            lines.add(Messages.text("usage.allowance", Long.toString(limits.inputAllowance())));
            lines.add(Messages.text("usage.share"));
            lines.add(Messages.text("usage.estimate"));
            lines.add(Messages.text("usage.compact"));
            lines.add("");
        }
        lines.add(Messages.text("usage.by_provider"));
        snapshot.providers().forEach((k, v) -> lines.add(k + ": " + TokenUsageSidebar.counter(v)));
        lines.add(Messages.text("usage.by_model"));
        snapshot.models().forEach((k, v) -> lines.add(k + ": " + TokenUsageSidebar.counter(v)));
        var wrapped =
                new ArrayList<>(
                        lines.stream()
                                .flatMap(
                                        line ->
                                                ChatRenderer.wrapProse(line, Math.max(1, width - 2))
                                                        .stream())
                                .toList());
        visible = Math.max(1, height - 3);
        max = Math.max(0, wrapped.size() - visible);
        scroll = Math.min(scroll, max);
        canvas.fill(x, y, width, height, INPUT);
        for (int i = scroll; i < Math.min(wrapped.size(), scroll + height - 3); i++)
            canvas.text(
                    x + 1,
                    y + 1 + i - scroll,
                    ChatRenderer.fit(wrapped.get(i), Math.max(0, width - 2)),
                    SUB,
                    INPUT,
                    false);
        canvas.text(
                x + 1,
                y + height - 1,
                ChatRenderer.fit(Messages.text("usage.close"), Math.max(0, width - 2)),
                BLUE,
                INPUT,
                false);
        return new ChatRenderer.Screen(canvas, 0, 0, false);
    }
}
