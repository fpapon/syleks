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
package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.fs.SecureFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exact, bounded patches. All paths, snapshots and permissions are checked before the first write. */
final class PatchTool {
    private record Change(Path path, byte[] before, String after) {}

    private final AgentTools files;

    PatchTool(Path workspace) {
        files = new AgentTools(workspace);
    }

    String execute(String patch, ToolContext context) throws Exception {
        if (patch.indexOf(0) >= 0 || patch.getBytes(StandardCharsets.UTF_8).length > 1_000_000)
            throw new IOException("Patch exceeds text limits");
        var lines = patch.replace("\r\n", "\n").split("\n", -1);
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) end--;
        if (end < 3
                || !lines[0].equals("*** Begin Patch")
                || !lines[end - 1].equals("*** End Patch"))
            throw new IOException("Expected *** Begin Patch / *** End Patch envelope");
        var changes = new ArrayList<Change>();
        var seen = new HashSet<Path>();
        for (int i = 1; i < end - 1; ) {
            var header = lines[i++];
            String kind;
            if (header.startsWith("*** Add File: ")) kind = "Add";
            else if (header.startsWith("*** Update File: ")) kind = "Update";
            else if (header.startsWith("*** Delete File: ")) kind = "Delete";
            else throw new IOException("Expected Add/Update/Delete File header");
            var path = files.path(header.substring(header.indexOf(": ") + 2));
            if (!seen.add(path) || changes.size() >= 64)
                throw new IOException("Duplicate path or more than 64 files");
            boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            context.require("apply_patch", path.toString(), patch);
            context.require(kind.equals("Update") ? "edit" : "write", path.toString(), header);
            if (kind.equals("Add") == exists)
                throw new IOException(
                        "Add requires absent file; update/delete require existing file");
            String original = exists ? files.text(path) : "";
            var before = exists ? original.getBytes(StandardCharsets.UTF_8) : null;
            int from = i;
            while (i < end - 1 && !lines[i].startsWith("*** ")) i++;
            // End-of-file is a hunk marker, not a file header.
            if (i < end - 1 && lines[i].equals("*** End of File")) i++;
            String after;
            if (kind.equals("Delete")) {
                if (i != from) throw new IOException("Delete does not accept a body");
                after = null;
            } else if (kind.equals("Add")) {
                var content = new StringBuilder();
                for (int j = from; j < i; j++) {
                    if (!lines[j].startsWith("+"))
                        throw new IOException("Added lines must start with +");
                    content.append(lines[j].substring(1)).append('\n');
                }
                after = content.toString();
            } else {
                after = update(original, lines, from, i);
            }
            if (after != null && after.getBytes(StandardCharsets.UTF_8).length > 1_000_000)
                throw new IOException("Patch result exceeds 1 MB");
            if (!Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Parent directory must exist");
            changes.add(new Change(path, before, after));
        }
        if (changes.isEmpty()) throw new IOException("Empty patch");
        // Revalidate the entire batch after approvals: never silently overwrite external changes.
        for (var change : changes) {
            files.path(change.path().toString());
            if (change.before() == null
                    ? Files.exists(change.path(), LinkOption.NOFOLLOW_LINKS)
                    : !Arrays.equals(change.before(), files.bytes(change.path())))
                throw new IOException("File changed during patch; retry");
        }
        for (var change : changes) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Patch cancelled");
            files.path(change.path().toString());
            if (change.before() != null
                    && !Arrays.equals(change.before(), files.bytes(change.path())))
                throw new IOException(
                        "File changed during patch commit; earlier files may have been applied");
            context.mutations()
                    .commit(
                            change.path(),
                            change.before(),
                            change.after() == null
                                    ? null
                                    : change.after().getBytes(StandardCharsets.UTF_8),
                            () -> {
                                SecureFiles.commit(
                                        change.path(),
                                        change.before(),
                                        change.after() == null
                                                ? null
                                                : change.after().getBytes(StandardCharsets.UTF_8));
                            });
        }
        return "Applied patch to " + changes.size() + " file(s)";
    }

    private String update(String original, String[] lines, int from, int end) throws IOException {
        boolean crlf = original.contains("\r\n");
        if (crlf && original.replace("\r\n", "").indexOf('\n') >= 0)
            throw new IOException("Mixed line endings are unsupported");
        String content = crlf ? original.replace("\r\n", "\n") : original;
        if (content.indexOf('\r') >= 0)
            throw new IOException("Mixed or CR-only line endings are unsupported");
        int cursor = 0;
        if (from == end) throw new IOException("Update requires a hunk");
        while (from < end) {
            if (!lines[from].equals("@@") && !lines[from].startsWith("@@ "))
                throw new IOException("Expected @@ hunk header");
            from++;
            var oldText = new StringBuilder();
            var newText = new StringBuilder();
            boolean eof = false, changed = false;
            while (from < end && !lines[from].startsWith("@@")) {
                var line = lines[from++];
                if (line.equals("*** End of File")) {
                    eof = true;
                    break;
                }
                if (line.isEmpty() || " +-".indexOf(line.charAt(0)) < 0)
                    throw new IOException("Malformed patch line");
                char prefix = line.charAt(0);
                if (prefix != '+') oldText.append(line.substring(1)).append('\n');
                if (prefix != '-') newText.append(line.substring(1)).append('\n');
                changed |= prefix != ' ';
            }
            if (!changed || oldText.isEmpty())
                throw new IOException("Update requires nonempty exact context and a change");
            String old = oldText.toString(), replacement = newText.toString();
            if (eof && !content.endsWith("\n")) {
                old = old.substring(0, old.length() - 1);
                if (!replacement.isEmpty())
                    replacement = replacement.substring(0, replacement.length() - 1);
            }
            int index = content.indexOf(old, cursor);
            if (index < 0
                    || content.indexOf(old, index + 1) >= 0
                    || eof && index + old.length() != content.length())
                throw new IOException("Patch context missing, ambiguous or not at EOF");
            content =
                    content.substring(0, index)
                            + replacement
                            + content.substring(index + old.length());
            cursor = index + replacement.length();
        }
        return crlf ? content.replace("\n", "\r\n") : content;
    }
}
