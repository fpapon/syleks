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

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.LocalCommandRunner;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandSandboxPolicyTest {
    @TempDir Path root;

    @Test
    void defaultsAreRestrictedAndNetworkDeny() {
        var policy = CommandSandboxPolicy.parse(Map.of());
        assertEquals("restricted", policy.mode());
        assertEquals("deny", policy.network());
        assertEquals(
                new CommandExecutionBackend.Capabilities(true, true, true, true),
                policy.backend(root).capabilities());
        var unrestricted = CommandSandboxPolicy.parse(Map.of("mode", "unrestricted"));
        assertTrue(unrestricted.description().contains("UNRESTRICTED"));
        assertEquals(
                new CommandExecutionBackend.Capabilities(false, false, false, false),
                unrestricted.backend(root).capabilities());
    }

    @Test
    void rejectsMalformedUnknownAndUnboundedPolicy() {
        for (Object settings :
                List.of(
                        "unrestricted",
                        List.of(),
                        Map.of("backend", "/tmp/fake"),
                        Map.of("mode", "auto"),
                        Map.of("network", true),
                        Map.of("network", "local"),
                        Map.of("memory_mib", 63),
                        Map.of("memory_mib", 16385),
                        Map.of("processes", 7),
                        Map.of("processes", 513),
                        Map.of("cpu_percent", 9),
                        Map.of("cpu_percent", 401),
                        Map.of("memory_mib", 64.5),
                        Map.of("processes", "64"),
                        Collections.singletonMap("mode", null)))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CommandSandboxPolicy.parse(settings),
                    settings.toString());
    }

    @Test
    void unavailableBackendFailsBeforeAnyPrefixAndNeverFallsBack() throws Exception {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "unsupported");
            assertThrows(
                    Exception.class, () -> new LocalCommandRunner(root).execute("touch prefix", 5));
            assertFalse(Files.exists(root.resolve("prefix")));
        } finally {
            System.setProperty("os.name", original);
        }
    }

    @Test
    void launchFailureNeverExecutesPrefix() throws Exception {
        var backend =
                new CommandExecutionBackend() {
                    public Capabilities capabilities() {
                        return new Capabilities(true, true, true, true);
                    }

                    public Execution start(
                            Path workspace, Map<String, String> env, String command, int timeout)
                            throws Exception {
                        throw new java.io.IOException("fixture setup failure");
                    }
                };
        var env =
                fr.openobject.ai.syleks.app.SubprocessEnvironment.commands(
                        Map.of(), Map.of(), root.resolve("home"));
        assertThrows(
                java.io.IOException.class,
                () -> new LocalCommandRunner(root, env, backend).execute("touch prefix", 5));
        assertFalse(Files.exists(root.resolve("prefix")));
    }
}
