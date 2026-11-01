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

import fr.openobject.ai.syleks.json.JsonIO;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerCommandBackendTest {
    @TempDir Path root;
    private final String image = "example.invalid/syleks@sha256:" + "a".repeat(64);

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "SYLEKS_CONTAINER_TEST_IMAGE",
            matches = ".+@sha256:[a-f0-9]{64}")
    void realEngineEnforcesWorkspacePolicyAndKillsBackgroundChildren() throws Exception {
        String engine = System.getenv().getOrDefault("SYLEKS_CONTAINER_TEST_BACKEND", "docker");
        var policy =
                CommandSandboxPolicy.parse(
                        Map.of(
                                "backend",
                                engine,
                                "container_image",
                                System.getenv("SYLEKS_CONTAINER_TEST_IMAGE")));
        var workspace = java.nio.file.Files.createDirectory(root.resolve("workspace"));
        java.nio.file.Files.createDirectories(workspace.resolve(".syleks"));
        java.nio.file.Files.writeString(workspace.resolve(".syleks/secret"), "hidden");
        java.nio.file.Files.createDirectories(workspace.resolve(".git"));
        java.nio.file.Files.writeString(workspace.resolve(".git/config"), "hidden");
        var backend = new ContainerCommandBackend(policy, Path.of(System.getProperty("user.home")));
        try (var run =
                backend.start(
                        workspace,
                        Map.of("PATH", "/usr/bin:/bin"),
                        "test ! -e .syleks/secret && test ! -e .git/config && test ! -e /var/run/docker.sock && "
                                + "test \"$HOME\" = /syleks-home && ! touch .syleks/forbidden && "
                                + "error=$(bash -c 'echo hello >/dev/tcp/127.0.0.1/1' 2>&1); "
                                + "[[ $error == *'Operation not permitted'* ]] && echo verified >result; "
                                + "(sleep 2; touch escaped) >/dev/null 2>&1 &",
                        5)) {
            assertTrue(run.process().waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(run.process().getInputStream().readAllBytes());
            assertEquals(0, run.process().exitValue(), output);
        }
        assertEquals("verified\n", java.nio.file.Files.readString(workspace.resolve("result")));
        Thread.sleep(2500);
        assertFalse(java.nio.file.Files.exists(workspace.resolve("escaped")));
        assertFalse(java.nio.file.Files.exists(workspace.resolve(".syleks/forbidden")));
    }

    @Test
    void onlyExplicitKnownBackendsAndPinnedImagesAreAccepted() {
        for (String engine : List.of("docker", "podman")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CommandSandboxPolicy.parse(Map.of("backend", engine)));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            CommandSandboxPolicy.parse(
                                    Map.of("backend", engine, "container_image", "debian:latest")));
            var policy =
                    CommandSandboxPolicy.parse(Map.of("backend", engine, "container_image", image));
            assertInstanceOf(ContainerCommandBackend.class, policy.backend(root));
            assertTrue(policy.description().contains(engine));
        }
    }

    @Test
    void confinesBothEnginesWithoutPullsHostEnvironmentOrShellInterpolation() throws Exception {
        for (String engine : List.of("docker", "podman")) {
            var policy =
                    CommandSandboxPolicy.parse(Map.of("backend", engine, "container_image", image));
            var args =
                    new ContainerCommandBackend(policy, root)
                            .arguments(
                                    root,
                                    Map.of(
                                            "HOME",
                                            "/host/home",
                                            "SSH_AUTH_SOCK",
                                            "/host/socket",
                                            "SAFE",
                                            "value"),
                                    "touch unsafe; echo done",
                                    5,
                                    root.resolve("profile"),
                                    "fixture");
            for (String flag :
                    List.of(
                            "--pull=never",
                            "--read-only",
                            "--network=none",
                            "--cap-drop=ALL",
                            "--cgroupns=private",
                            "--memory-swap=1073741824",
                            "HOME=/syleks-home",
                            "SAFE=value")) assertTrue(args.contains(flag), flag);
            assertFalse(args.contains("SSH_AUTH_SOCK=/host/socket"));
            assertFalse(args.contains("HOME=/host/home"));
            assertEquals("touch unsafe; echo done", args.getLast());
            assertTrue(args.indexOf(image) < args.indexOf("sandbox-gate"));
        }
    }

    @Test
    void seccompDeniesSocketsUnlessExplicitlyAllowedAndAlwaysBlocksUnixSockets() throws Exception {
        var json = new JsonIO();
        for (boolean network : List.of(false, true)) {
            var profile = json.parse(ContainerCommandBackend.seccomp(network));
            assertEquals("SCMP_ARCH_X86_64", profile.path("architectures").get(0).asText());
            boolean unixDenied = false, inetDenied = false;
            for (var rule : profile.path("syscalls")) {
                if (!rule.path("names").get(0).asText().equals("socket")) continue;
                var args = rule.path("args");
                if (!rule.has("args")) {
                    unixDenied = inetDenied = true;
                    continue;
                }
                for (var arg : args) {
                    if (arg.path("op").asText().equals("SCMP_CMP_LT")
                            && arg.path("value").asInt() == 2) unixDenied = true;
                }
            }
            assertTrue(unixDenied);
            assertEquals(!network, inetDenied);
        }
    }
}
