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
package fr.openobject.ai.syleks.app;

import fr.openobject.ai.syleks.ai.ApprovalGrants;
import java.nio.file.*;
import java.util.*;

/** Detect live policy/trust changes. Never reload authority while a reviewed action is pending. */
final class ApprovalPolicyRevision {
    static String read(Path home, Path workspace) {
        var values = new LinkedHashMap<String, Object>();
        Path directory = home.resolve(".config/syleks");
        for (String name : List.of("agent-config.json", "AGENTS.md"))
            values.put(name, ConfigurationFiles.optional(directory.resolve(name), 1024 * 1024));
        Path agents = directory.resolve("agents");
        if (Files.exists(agents, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(agents)) {
                var definitions =
                        files.filter(path -> path.toString().endsWith(".md"))
                                .sorted()
                                .limit(129)
                                .toList();
                if (definitions.size() > 128)
                    throw new IllegalStateException("Too many agent definitions");
                for (var path : definitions)
                    values.put(
                            path.toString(),
                            ConfigurationFiles.read(path, ConfigurationFiles.TEXT_LIMIT));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Unable to inspect approval policy", e);
            }
        }
        var trust = new ProjectTrust(home, workspace);
        values.put("project", trust.approvalRevision());
        values.put("trusted", trust.trusted());
        return ApprovalGrants.digest(values);
    }
}
