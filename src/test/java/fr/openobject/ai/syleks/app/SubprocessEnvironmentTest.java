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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubprocessEnvironmentTest {
    @TempDir Path root;

    @Test
    void excludesUnknownSecretsHooksSocketsAndBuildSettingsUnlessExplicitlySupported()
            throws Exception {
        var host =
                Map.of(
                        "UNKNOWN_SECRET",
                        "fake",
                        "JAVA_HOME",
                        "/fake/jdk",
                        "SSH_AUTH_SOCK",
                        "/fake/socket");
        var baseline = SubprocessEnvironment.commands(Map.of(), host, root).forWorkspace(root);
        assertFalse(baseline.containsKey("UNKNOWN_SECRET"));
        assertFalse(baseline.containsKey("JAVA_HOME"));
        assertFalse(baseline.containsKey("SSH_AUTH_SOCK"));
        assertEquals("/usr/bin:/bin", baseline.get("PATH"));
        assertEquals(root.toString(), baseline.get("HOME"));
        var opted =
                SubprocessEnvironment.commands(Map.of("pass_env", List.of("JAVA_HOME")), host, root)
                        .forWorkspace(root);
        assertEquals("/fake/jdk", opted.get("JAVA_HOME"));
        for (var key :
                List.of(
                        "BASH_ENV",
                        "BASH_FUNC_printf%%",
                        "ENV",
                        "SHELLOPTS",
                        "BASHOPTS",
                        "LD_PRELOAD",
                        "DYLD_INSERT_LIBRARIES",
                        "PYTHONPATH",
                        "PYTHONSTARTUP",
                        "NODE_OPTIONS",
                        "PERL5OPT",
                        "RUBYOPT",
                        "JAVA_TOOL_OPTIONS",
                        "JDK_JAVA_OPTIONS",
                        "_JAVA_OPTIONS",
                        "PATH",
                        "UNKNOWN_SECRET")) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    SubprocessEnvironment.commands(
                                            Map.of("pass_env", List.of(key)),
                                            Map.of(key, "secret-sentinel"),
                                            root));
            assertFalse(error.getMessage().contains("secret-sentinel"));
        }
    }

    @Test
    void rejectsRelativeEmptyMissingAndWorkspaceExecutablePathsIncludingSymlinks()
            throws Exception {
        for (var path : List.of(".", "", "relative", "/usr/bin:.", root.toString())) {
            assertThrows(
                    Exception.class,
                    () ->
                            SubprocessEnvironment.commands(
                                            Map.of("trusted_path", List.of(path)), Map.of(), root)
                                    .forWorkspace(root));
        }
        var workspace = Files.createDirectory(root.resolve("workspace"));
        var link = Files.createSymbolicLink(root.resolve("link"), workspace);
        assertThrows(
                java.io.IOException.class,
                () ->
                        SubprocessEnvironment.commands(
                                        Map.of("trusted_path", List.of(link.toString())),
                                        Map.of(),
                                        root)
                                .forWorkspace(workspace));
        assertThrows(
                java.io.IOException.class,
                () ->
                        SubprocessEnvironment.commands(
                                        Map.of(
                                                "trusted_path",
                                                List.of(root.resolve("missing").toString())),
                                        Map.of(),
                                        root)
                                .forWorkspace(workspace));
    }

    @Test
    void discoversJavaAndRunsABuildToolWithExplicitTrustedPath() throws Exception {
        var bin = Path.of(System.getProperty("java.home"), "bin");
        var policy =
                SubprocessEnvironment.commands(
                        Map.of(
                                "trusted_path",
                                List.of(bin.toString(), "/usr/bin", "/bin"),
                                "pass_env",
                                List.of("JAVA_HOME")),
                        Map.of("JAVA_HOME", bin.getParent().toString()),
                        root);
        var runner = new fr.openobject.ai.syleks.ai.LocalCommandRunner(root, policy);
        assertEquals(0, runner.execute("java --version", 10).exitCode());
        Files.writeString(
                root.resolve("Hello.java"),
                "class Hello { public static void main(String[] a) { System.out.print(42); } }");
        assertEquals(0, runner.execute("javac Hello.java", 10).exitCode());
        assertEquals("42", runner.execute("java Hello", 10).output());
    }
}
