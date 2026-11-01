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

/** Pure presentation. No IO, mutable session state or tokenizer guesses. */
public final class TokenUsageSidebar {
    public static final int WIDTH = 32;

    public static boolean visible(int columns, int rows, UsagePreferences.Visibility preference) {
        return preference != UsagePreferences.Visibility.HIDE && columns >= 105 && rows >= 20;
    }

    public static String format(Long value) {
        if (value == null) return Messages.text("usage.na");
        if (value < 1000) return value.toString();
        return String.format(
                Locale.ROOT,
                "%.1f%s",
                value / (value < 1_000_000 ? 1000d : 1_000_000d),
                value < 1_000_000 ? "K" : "M");
    }

    private static String usageStatus(LlmUsageEvent.Status status) {
        String key = "usage.status." + status.name();
        String value = Messages.text(key);
        return value.equals(key) ? status.name() : value;
    }

    public static String counter(TokenUsageAggregator.Counter value) {
        return (value.estimated() ? "~" : "")
                + format(value.value())
                + (value.partial() ? "*" : "");
    }

    private static String row(String label, String value, int width) {
        value = ChatRenderer.fit(value, width);
        return ChatRenderer.fit(label, Math.max(0, width - ChatRenderer.textWidth(value) - 1))
                + " "
                        .repeat(
                                Math.max(
                                        1,
                                        width
                                                - ChatRenderer.textWidth(value)
                                                - Math.min(
                                                        ChatRenderer.textWidth(label),
                                                        Math.max(
                                                                0,
                                                                width
                                                                        - ChatRenderer.textWidth(
                                                                                value)
                                                                        - 1))))
                + value;
    }

    public static List<String> lines(
            TokenUsageAggregator.Snapshot s, String provider, String model, int width) {
        var lines = new ArrayList<String>();
        lines.add(Messages.text("usage.title"));
        lines.add(
                s.last() != null && s.last().status() == LlmUsageEvent.Status.RUNNING
                        ? Messages.text("usage.pending")
                        : "");
        lines.add(Messages.text("usage.session"));
        lines.add(row(Messages.text("usage.total"), counter(s.total()), width));
        lines.add(row(Messages.text("usage.input"), counter(s.input()), width));
        lines.add(row(Messages.text("usage.output"), counter(s.output()), width));
        lines.add(row(Messages.text("usage.cached"), counter(s.cached()), width));
        lines.add(row(Messages.text("usage.reasoning"), counter(s.reasoning()), width));
        lines.add(row(Messages.text("usage.requests"), Long.toString(s.requests()), width));
        lines.add("");
        lines.add(Messages.text("usage.context"));
        var context = ContextMetricsService.snapshot(s.last(), provider, model);
        lines.add(
                row(
                        (s.last() != null
                                                && s.last().usage().source()
                                                        == TokenUsage.Source.ESTIMATED
                                        ? "~"
                                        : "")
                                + format(context.used())
                                + " / "
                                + format(context.capacity()),
                        context.percentage() == null
                                ? Messages.text("usage.na")
                                : String.format(Locale.ROOT, "%.0f%%", context.percentage()),
                        width));
        int filled =
                context.percentage() == null
                        ? 0
                        : (int) Math.round(Math.clamp(context.percentage(), 0, 100) * width / 100d);
        lines.add(
                context.percentage() == null
                        ? Messages.text("usage.na")
                        : "█".repeat(filled) + "░".repeat(width - filled));
        lines.add(Messages.text("usage.last_input"));
        lines.add(row(Messages.text("usage.remaining"), format(context.remaining()), width));
        lines.add("");
        lines.add(Messages.text("usage.last"));
        var last = s.last();
        var usage = last == null ? TokenUsage.unavailable() : last.usage();
        String marker = usage.source() == TokenUsage.Source.ESTIMATED ? "~" : "";
        lines.add(last == null ? Messages.text("usage.na") : usageStatus(last.status()));
        lines.add(row(Messages.text("usage.input"), marker + format(usage.inputTokens()), width));
        lines.add(row(Messages.text("usage.output"), marker + format(usage.outputTokens()), width));
        if (usage.cachedInputTokens() != null)
            lines.add(
                    row(
                            Messages.text("usage.cached"),
                            marker + format(usage.cachedInputTokens()),
                            width));
        if (usage.reasoningOutputTokens() != null)
            lines.add(
                    row(
                            Messages.text("usage.reasoning"),
                            marker + format(usage.reasoningOutputTokens()),
                            width));
        lines.add(row(Messages.text("usage.total"), marker + format(usage.totalTokens()), width));
        lines.add("");
        lines.add(Messages.text("usage.performance"));
        lines.add(
                row(
                        Messages.text(
                                last != null
                                                && last.generationDurationNs() != null
                                                && last.generationDurationNs() > 0
                                        ? "usage.speed_provider"
                                        : "usage.speed_client"),
                        last == null || last.tokensPerSecond() == null
                                ? Messages.text("usage.na")
                                : String.format(Locale.ROOT, "%.1f tok/s", last.tokensPerSecond()),
                        width));
        lines.add(
                row(
                        Messages.text("usage.total_client"),
                        last == null || last.durationMs() == null
                                ? Messages.text("usage.na")
                                : last.durationMs() + " ms",
                        width));
        lines.add(
                row(
                        Messages.text("usage.first"),
                        last == null || last.firstTokenLatencyMs() == null
                                ? Messages.text("usage.na")
                                : last.firstTokenLatencyMs() + " ms",
                        width));
        if (last != null && last.providerDurationNs() != null)
            lines.add(
                    row(
                            Messages.text("usage.total_provider"),
                            String.format(Locale.ROOT, "%.3f s", last.providerDurationNs() / 1e9),
                            width));
        if (last != null && last.generationDurationNs() != null)
            lines.add(
                    row(
                            Messages.text("usage.generation_provider"),
                            String.format(Locale.ROOT, "%.3f s", last.generationDurationNs() / 1e9),
                            width));
        lines.add("");
        lines.add(Messages.text("usage.provider"));
        lines.add(
                last == null
                        ? provider + " / " + model
                        : last.providerId() + " / " + last.modelId());
        lines.add(Messages.text("usage.legend"));
        return List.copyOf(lines);
    }

    public void paint(
            TerminalCanvas canvas,
            TokenUsageAggregator.Snapshot snapshot,
            String provider,
            String model,
            int left,
            int top,
            int width,
            int height,
            double warning,
            double critical) {
        var lines = lines(snapshot, provider, model, width - 3);
        var context = ContextMetricsService.snapshot(snapshot.last(), provider, model);
        if (lines.size() > height) {
            var compact = new ArrayList<String>();
            compact.add(lines.get(0));
            if (snapshot.last() != null && snapshot.last().status() == LlmUsageEvent.Status.RUNNING)
                compact.add(Messages.text("usage.pending"));
            compact.add(Messages.text("usage.session"));
            compact.add(lines.get(3));
            compact.add(lines.get(4));
            compact.add(lines.get(5));
            compact.add(lines.get(8));
            compact.add(Messages.text("usage.context"));
            compact.add(lines.get(11));
            compact.add(lines.get(12));
            compact.add(Messages.text("usage.last_input_short"));
            compact.add(Messages.text("usage.last"));
            var last = snapshot.last();
            var u = last == null ? TokenUsage.unavailable() : last.usage();
            compact.add(
                    row(
                            Messages.text("usage.input_output"),
                            (u.source() == TokenUsage.Source.ESTIMATED ? "~" : "")
                                    + format(u.inputTokens())
                                    + " / "
                                    + format(u.outputTokens()),
                            width - 3));
            compact.add(Messages.text("usage.performance"));
            int perf = lines.indexOf(Messages.text("usage.performance"));
            compact.add(lines.get(perf + 1));
            compact.add(lines.get(perf + 2));
            compact.add(lines.get(perf + 3));
            compact.add(Messages.text("usage.provider"));
            compact.add(lines.get(lines.size() - 2));
            compact.add(Messages.text("usage.more"));
            lines = compact;
        }
        for (int y = 0; y < height; y++) canvas.text(left, top + y, "│", BORDER, BG, false);
        // On short terminals retain session totals and context; /usage provides the full detail.
        for (int i = 0; i < Math.min(height, lines.size()); i++) {
            String line =
                    i == height - 1 && lines.size() > height
                            ? Messages.text("usage.details")
                            : lines.get(i);
            int color =
                    line.startsWith("█") || line.startsWith("░")
                            ? context.percentage() != null && context.percentage() > critical
                                    ? RED
                                    : context.percentage() != null
                                                    && context.percentage() >= warning
                                            ? 0xFDBA74
                                            : BLUE
                            : Set.of(
                                                    Messages.text("usage.title"),
                                                    Messages.text("usage.session"),
                                                    Messages.text("usage.context"),
                                                    Messages.text("usage.last"),
                                                    Messages.text("usage.performance"),
                                                    Messages.text("usage.provider"))
                                            .contains(line)
                                    ? BLUE
                                    : SUB;
            canvas.text(left + 2, top + i, ChatRenderer.fit(line, width - 3), color, BG, false);
        }
    }
}
