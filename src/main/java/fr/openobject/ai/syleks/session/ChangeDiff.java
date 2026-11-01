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

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Bounded, contextual snapshot review. Large edits avoid quadratic allocation. */
final class ChangeDiff {
    private record Line(char kind, String text) {}

    private static final int LIMIT = 32_000;

    static String render(List<FileChangeJournal.Change> changes) {
        var output = new StringBuilder();
        for (var change : changes) {
            String before = decode(change.before()), after = decode(change.after());
            // Escape header controls: file names must never inject review rows or terminal
            // controls.
            String path =
                    change.path()
                            .replace("\\", "\\\\")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t");
            output.append("--- ")
                    .append(path)
                    .append(" (before)\n+++ ")
                    .append(path)
                    .append(" (after, ")
                    .append(change.state())
                    .append(")\n");
            String operation =
                    change.before() == null
                            ? "Added"
                            : change.after() == null ? "Deleted" : "Modified";
            if (before == null || after == null) {
                output.append(operation)
                        .append(" binary file: ")
                        .append(size(change.before()))
                        .append(" → ")
                        .append(size(change.after()))
                        .append(" bytes\n");
            } else {
                var lines = compare(before, after);
                long added = lines.stream().filter(l -> l.kind() == '+').count();
                long removed = lines.stream().filter(l -> l.kind() == '-').count();
                output.append(operation)
                        .append(" · +")
                        .append(added)
                        .append(" -")
                        .append(removed)
                        .append('\n');
                int oldLine = 1, newLine = 1, index = 0;
                while (index < lines.size()) {
                    int first = index;
                    while (first < lines.size() && lines.get(first).kind() == ' ') first++;
                    if (first == lines.size()) break;
                    int start = Math.max(index, first - 3), end = first + 1;
                    while (end < lines.size()) {
                        int next = end;
                        while (next < lines.size() && lines.get(next).kind() == ' ') next++;
                        if (next - end > 6 || next == lines.size()) {
                            end = Math.min(lines.size(), end + 3);
                            break;
                        }
                        end = next + 1;
                    }
                    for (; index < start; index++) {
                        oldLine++;
                        newLine++;
                    }
                    int oldCount = 0, newCount = 0;
                    for (int i = start; i < end; i++) {
                        if (lines.get(i).kind() != '+') oldCount++;
                        if (lines.get(i).kind() != '-') newCount++;
                    }
                    output.append("@@ -")
                            .append(oldCount == 0 ? oldLine - 1 : oldLine)
                            .append(',')
                            .append(oldCount)
                            .append(" +")
                            .append(newCount == 0 ? newLine - 1 : newLine)
                            .append(',')
                            .append(newCount)
                            .append(" @@\n");
                    for (; index < end; index++) {
                        var line = lines.get(index);
                        String text = line.text();
                        output.append(line.kind())
                                .append(text, 0, Math.min(text.length(), LIMIT + 1))
                                .append(
                                        text.endsWith("\n")
                                                ? ""
                                                : "\n\\ No newline at end of file\n");
                        if (line.kind() != '+') oldLine++;
                        if (line.kind() != '-') newLine++;
                        if (output.length() > LIMIT)
                            return output.substring(0, LIMIT) + "\n[Diff truncated]";
                    }
                }
            }
            if (output.length() > LIMIT) return output.substring(0, LIMIT) + "\n[Diff truncated]";
        }
        return output.isEmpty() ? "No recorded native file changes" : output.toString();
    }

    private static int size(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }

    private static String decode(byte[] bytes) {
        if (bytes == null) return "";
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            return text.indexOf('\0') >= 0 ? null : text;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static List<Line> compare(String before, String after) {
        var a = before.isEmpty() ? new String[0] : before.split("(?<=\n)");
        var b = after.isEmpty() ? new String[0] : after.split("(?<=\n)");
        int prefix = 0, suffix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) prefix++;
        while (suffix < a.length - prefix
                && suffix < b.length - prefix
                && a[a.length - suffix - 1].equals(b[b.length - suffix - 1])) suffix++;
        var result = new ArrayList<Line>();
        for (int i = 0; i < prefix; i++) result.add(new Line(' ', a[i]));
        int n = a.length - prefix - suffix, m = b.length - prefix - suffix;
        if (n > 0 && m > 0 && (long) (n + 1) * (m + 1) <= 1_000_000) {
            var lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--)
                for (int j = m - 1; j >= 0; j--)
                    lcs[i][j] =
                            a[prefix + i].equals(b[prefix + j])
                                    ? 1 + lcs[i + 1][j + 1]
                                    : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            int i = 0, j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && a[prefix + i].equals(b[prefix + j])) {
                    result.add(new Line(' ', a[prefix + i++]));
                    j++;
                } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]))
                    result.add(new Line('-', a[prefix + i++]));
                else result.add(new Line('+', b[prefix + j++]));
            }
        } else {
            for (int i = prefix; i < a.length - suffix; i++) result.add(new Line('-', a[i]));
            for (int i = prefix; i < b.length - suffix; i++) result.add(new Line('+', b[i]));
        }
        for (int i = a.length - suffix; i < a.length; i++) result.add(new Line(' ', a[i]));
        return result;
    }
}
