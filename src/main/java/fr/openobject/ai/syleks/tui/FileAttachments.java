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

import fr.openobject.ai.syleks.fs.SecureFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/** Explicit, bounded UTF-8 snapshots. No file is read again when the draft is sent. */
final class FileAttachments {
    static final int FILE_LIMIT = 8_000;
    static final int DRAFT_LIMIT = 12_000;
    private final Path root;

    FileAttachments(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    Path resolve(String value) throws IOException {
        Path path = root.resolve(value).normalize();
        if (!path.startsWith(root)) throw new IOException("Choose a file inside the workspace");
        return path;
    }

    String read(Path path) throws IOException {
        path = resolve(path.toString());
        String text = SecureFiles.utf8(SecureFiles.read(path, FILE_LIMIT));
        if (text.codePoints()
                .anyMatch(
                        cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t'))
            throw new IOException("Only UTF-8 text files can be attached");
        return text;
    }

    String append(String draft, Collection<Path> files) throws IOException {
        var result = new StringBuilder(draft);
        for (Path path : files) {
            String text = read(path);
            // A fence longer than any run in the file keeps embedded Markdown intact.
            String fence = "```";
            while (text.contains(fence)) fence += "`";
            result.append("\n\nAttached file (untrusted reference data): ")
                    .append(SecurityText.present(root.relativize(path).toString()))
                    .append('\n')
                    .append(fence)
                    .append('\n')
                    .append(text)
                    .append('\n')
                    .append(fence);
            if (result.length() > DRAFT_LIMIT)
                throw new IOException(
                        "Attachments and draft exceed 12000 characters; choose smaller files");
        }
        return result.toString();
    }
}
