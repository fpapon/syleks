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
package fr.openobject.ai.syleks.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Process authority is independent of shell permission parsing. */
public interface CommandExecutionBackend {
    record Capabilities(
            boolean filesystem, boolean network, boolean resources, boolean containedCleanup) {}

    Capabilities capabilities();

    Execution start(
            Path workspace, Map<String, String> environment, String command, int timeoutSeconds)
            throws Exception;

    interface Execution extends AutoCloseable {
        Process process();

        @Override
        void close() throws Exception;
    }

    /** Call only after an explicit global user choice (or within isolated runner tests). */
    static CommandExecutionBackend unrestricted() {
        return new CommandExecutionBackend() {
            public Capabilities capabilities() {
                return new Capabilities(false, false, false, false);
            }

            public Execution start(
                    Path workspace, Map<String, String> environment, String command, int timeout)
                    throws Exception {
                var builder =
                        new ProcessBuilder(
                                        List.of(
                                                "/bin/bash",
                                                "--noprofile",
                                                "--norc",
                                                "-c",
                                                command))
                                .directory(workspace.toFile())
                                .redirectErrorStream(true);
                builder.environment().clear();
                builder.environment().putAll(environment);
                var process = builder.start();
                process.getOutputStream().close();
                return new Execution() {
                    public Process process() {
                        return process;
                    }

                    public void close() {
                        if (process.isAlive()) process.destroyForcibly();
                    }
                };
            }
        };
    }
}
