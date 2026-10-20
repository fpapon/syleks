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

import fr.openobject.ai.syleks.usage.*;
import java.util.*;

/** Pure presentation. No IO, mutable session state or tokenizer guesses. */
public final class TokenUsageSidebar {
    public static final int WIDTH = 32;

    public static boolean visible(int columns, int rows, UsagePreferences.Visibility preference) {
        return preference != UsagePreferences.Visibility.HIDE && columns >= 105 && rows >= 20;
    }

    public static String format(Long value) {
        if (value == null) return "N/A";
        if (value < 1000) return value.toString();
        return String.format(
                Locale.ROOT,
                "%.1f%s",
                value / (value < 1_000_000 ? 1000d : 1_000_000d),
                value < 1_000_000 ? "K" : "M");
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
        lines.add("TOKEN USAGE");
        lines.add(
                s.last() != null && s.last().status() == LlmUsageEvent.Status.RUNNING
                        ? "Generating · counting pending"
                        : "");
        lines.add("SESSION TOTAL");
        lines.add(row("Total", counter(s.total()), width));
        lines.add(row("Input", counter(s.input()), width));
        lines.add(row("Output", counter(s.output()), width));
        lines.add(row("Cached", counter(s.cached()), width));
        lines.add(row("Reasoning", counter(s.reasoning()), width));
        lines.add(row("Requests", Long.toString(s.requests()), width));
        lines.add("");
        lines.add("CONTEXT WINDOW");
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
                                ? "N/A"
                                : String.format(Locale.ROOT, "%.0f%%", context.percentage()),
                        width));
        int filled =
                context.percentage() == null
                        ? 0
                        : (int) Math.round(Math.clamp(context.percentage(), 0, 100) * width / 100d);
        lines.add(
                context.percentage() == null
                        ? "N/A"
                        : "█".repeat(filled) + "░".repeat(width - filled));
        lines.add("Last sent input; not live context");
        lines.add(row("Remaining", format(context.remaining()), width));
        lines.add("");
        lines.add("LAST REQUEST");
        var last = s.last();
        var usage = last == null ? TokenUsage.unavailable() : last.usage();
        String marker = usage.source() == TokenUsage.Source.ESTIMATED ? "~" : "";
        lines.add(last == null ? "N/A" : last.status().name());
        lines.add(row("Input", marker + format(usage.inputTokens()), width));
        lines.add(row("Output", marker + format(usage.outputTokens()), width));
        if (usage.cachedInputTokens() != null)
            lines.add(row("Cached", marker + format(usage.cachedInputTokens()), width));
        if (usage.reasoningOutputTokens() != null)
            lines.add(row("Reasoning", marker + format(usage.reasoningOutputTokens()), width));
        lines.add(row("Total", marker + format(usage.totalTokens()), width));
        lines.add("");
        lines.add("PERFORMANCE");
        lines.add(
                row(
                        "Speed "
                                + (last != null
                                                && last.generationDurationNs() != null
                                                && last.generationDurationNs() > 0
                                        ? "provider"
                                        : "client"),
                        last == null || last.tokensPerSecond() == null
                                ? "N/A"
                                : String.format(Locale.ROOT, "%.1f tok/s", last.tokensPerSecond()),
                        width));
        lines.add(
                row(
                        "Total client",
                        last == null || last.durationMs() == null
                                ? "N/A"
                                : last.durationMs() + " ms",
                        width));
        lines.add(
                row(
                        "First useful (client)",
                        last == null || last.firstTokenLatencyMs() == null
                                ? "N/A"
                                : last.firstTokenLatencyMs() + " ms",
                        width));
        if (last != null && last.providerDurationNs() != null)
            lines.add(
                    row(
                            "Total provider",
                            String.format(Locale.ROOT, "%.3f s", last.providerDurationNs() / 1e9),
                            width));
        if (last != null && last.generationDurationNs() != null)
            lines.add(
                    row(
                            "Generation provider",
                            String.format(Locale.ROOT, "%.3f s", last.generationDurationNs() / 1e9),
                            width));
        lines.add("");
        lines.add("PROVIDER / MODEL");
        lines.add(
                last == null
                        ? provider + " / " + model
                        : last.providerId() + " / " + last.modelId());
        lines.add("* partial · ~ estimated");
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
                compact.add("Generating · counting pending");
            compact.add("SESSION TOTAL");
            compact.add(lines.get(3));
            compact.add(lines.get(4));
            compact.add(lines.get(5));
            compact.add(lines.get(8));
            compact.add("CONTEXT WINDOW");
            compact.add(lines.get(11));
            compact.add(lines.get(12));
            compact.add("Last sent input");
            compact.add("LAST REQUEST");
            var last = snapshot.last();
            var u = last == null ? TokenUsage.unavailable() : last.usage();
            compact.add(
                    row(
                            "Input / Output",
                            (u.source() == TokenUsage.Source.ESTIMATED ? "~" : "")
                                    + format(u.inputTokens())
                                    + " / "
                                    + format(u.outputTokens()),
                            width - 3));
            compact.add("PERFORMANCE");
            int perf = lines.indexOf("PERFORMANCE");
            compact.add(lines.get(perf + 1));
            compact.add(lines.get(perf + 2));
            compact.add(lines.get(perf + 3));
            compact.add("PROVIDER / MODEL");
            compact.add(lines.get(lines.size() - 2));
            compact.add("/usage for all metrics");
            lines = compact;
        }
        for (int y = 0; y < height; y++) canvas.text(left, top + y, "│", BORDER, BG, false);
        // On short terminals retain session totals and context; /usage provides the full detail.
        for (int i = 0; i < Math.min(height, lines.size()); i++) {
            String line =
                    i == height - 1 && lines.size() > height ? "/usage for details" : lines.get(i);
            int color =
                    line.startsWith("█") || line.startsWith("░")
                            ? context.percentage() != null && context.percentage() > critical
                                    ? RED
                                    : context.percentage() != null
                                                    && context.percentage() >= warning
                                            ? 0xFDBA74
                                            : BLUE
                            : Set.of(
                                                    "TOKEN USAGE",
                                                    "SESSION TOTAL",
                                                    "CONTEXT WINDOW",
                                                    "LAST REQUEST",
                                                    "PERFORMANCE",
                                                    "PROVIDER / MODEL")
                                            .contains(line)
                                    ? BLUE
                                    : SUB;
            canvas.text(left + 2, top + i, ChatRenderer.fit(line, width - 3), color, BG, false);
        }
    }
}
