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
import fr.openobject.ai.syleks.app.SubprocessEnvironment;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Opt in only on a supported host. In required mode, unavailable enforcement is a failure, never a skip. */
@EnabledIfEnvironmentVariable(named = "SYLEKS_SANDBOX_TESTS", matches = "required")
class LinuxCommandBackendTest {
    @TempDir Path root;

    private Path workspace() throws Exception {
        return Files.createDirectories(root.resolve("project"));
    }

    private LocalCommandRunner runner(Map<String, Object> settings) throws Exception {
        var workspace = workspace();
        var home = Files.createDirectories(root.resolve("home"));
        return new LocalCommandRunner(
                workspace,
                SubprocessEnvironment.commands(Map.of(), Map.of(), home),
                CommandSandboxPolicy.parse(settings).backend(home));
    }

    private LocalCommandRunner.Result run(String command) throws Exception {
        return runner(Map.of()).execute(command, 10);
    }

    @Test
    void editsWorkspaceAndHidesHostFilesPolicyMetadataAndCaches() throws Exception {
        var workspace = workspace();
        var home = Files.createDirectories(root.resolve("home/.ssh"));
        Files.writeString(home.resolve("fake-key"), "SEC03_FAKE_CREDENTIAL");
        Files.writeString(root.resolve("outside"), "outside");
        Files.createDirectories(workspace.resolve(".syleks"));
        Files.writeString(workspace.resolve(".syleks/agent-config.json"), "policy");
        Files.createDirectories(workspace.resolve(".git/hooks"));
        Files.writeString(workspace.resolve(".git/hooks/fake"), "hook");
        Files.createSymbolicLink(workspace.resolve("escape"), root.resolve("outside"));
        var result =
                run(
                        """
                set -eu
                printf allowed > allowed
                test "$HOME" = /syleks-home
                test "$TMPDIR" = /tmp
                test ! -e '%s'
                test ! -e '%s'
                test ! -e .git/hooks/fake
                test ! -e .syleks/agent-config.json
                test ! -r escape
                printf ephemeral > escape
                test "$(cat escape)" = ephemeral
                if printf bad > .syleks/agent-config.json; then exit 21; fi
                if printf bad > .git/config; then exit 22; fi
                if printf bad > /usr/syleks-test; then exit 23; fi
                printf private > "$HOME/private"
                printf private > /tmp/syleks-private
                printf ok
                """
                                .formatted(home.resolve("fake-key"), root.resolve("outside")));
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("allowed", Files.readString(workspace.resolve("allowed")));
        assertEquals("outside", Files.readString(root.resolve("outside")));
        assertEquals("policy", Files.readString(workspace.resolve(".syleks/agent-config.json")));
        assertFalse(Files.exists(root.resolve("home/private")));
    }

    @Test
    void deniesInternetFamiliesHostUnixSocketsAndPrivilegePathsInEveryDescendant()
            throws Exception {
        var script = workspace().resolve("probe.py");
        Files.writeString(
                script,
                """
                import errno, os, socket, subprocess
                for family, target in [(socket.AF_INET, ('127.0.0.1', 1)),
                                       (socket.AF_INET, ('10.0.0.1', 1)),
                                       (socket.AF_INET, ('192.0.2.1', 1)),
                                       (socket.AF_INET6, ('::1', 1)),
                                       (socket.AF_UNIX, 'host.sock')]:
                    try:
                        s = socket.socket(family, socket.SOCK_STREAM)
                    except PermissionError:
                        continue
                    s.close()
                    raise AssertionError('socket creation permitted')
                assert subprocess.run(['/usr/bin/unshare', '-Ur', '/usr/bin/true']).returncode != 0
                assert subprocess.run(['/usr/bin/mount', '-t', 'tmpfs', 'tmpfs', '/tmp']).returncode != 0
                assert 'NoNewPrivs:\\t1' in open('/proc/self/status').read()
                """);
        var result =
                run(
                        "/usr/bin/python3 probe.py; status=$?; /usr/bin/python3 probe.py; exit $status");
        assertEquals(0, result.exitCode(), result.output());
    }

    @Test
    void networkGrantAllowsOnlyInternetSocketsAndReachesALocalFixture() throws Exception {
        try (var server =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            Files.writeString(
                    workspace().resolve("network.py"),
                    """
                    import socket
                    try:
                        socket.socket(socket.AF_UNIX)
                    except PermissionError:
                        pass
                    else:
                        raise AssertionError('host Unix socket permitted')
                    with socket.create_connection(('127.0.0.1', %d), timeout=2) as connection:
                        connection.sendall(b'fixture')
                    """
                            .formatted(server.getLocalPort()));
            var result =
                    runner(Map.of("network", "allow")).execute("/usr/bin/python3 network.py", 10);
            assertEquals(0, result.exitCode(), result.output());
            try (var accepted = server.accept()) {
                assertEquals("fixture", new String(accepted.getInputStream().readAllBytes()));
            }
            var denied = run("/usr/bin/python3 network.py");
            assertNotEquals(0, denied.exitCode());
            server.setSoTimeout(150);
            assertThrows(
                    java.net.SocketTimeoutException.class,
                    server::accept,
                    "Denied command must not reach the host fixture");
        }
    }

    @Test
    void boundsResourcesAndOutput() throws Exception {
        Files.writeString(
                workspace().resolve("limits.py"),
                """
                import os
                children = []
                try:
                    for i in range(128):
                        pid = os.fork()
                        if pid == 0:
                            import time
                            time.sleep(20)
                            os._exit(0)
                        children.append(pid)
                except BlockingIOError:
                    print('pids-bounded', flush=True)
                else:
                    raise AssertionError('process limit absent')
                finally:
                    import signal
                    for pid in children:
                        os.kill(pid, signal.SIGKILL)
                    for pid in children:
                        os.waitpid(pid, 0)
                """);
        var bounded = runner(Map.of("memory_mib", 64, "processes", 16, "cpu_percent", 50));
        var pids = bounded.execute("/usr/bin/python3 limits.py", 10);
        assertEquals(0, pids.exitCode(), pids.output());
        assertTrue(pids.output().contains("pids-bounded"));
        var memory = bounded.execute("/usr/bin/python3 -c 'x = bytearray(256 * 1024 * 1024)'", 10);
        assertNotEquals(0, memory.exitCode());
        var output = bounded.execute("printf '%050000d' 1", 10);
        assertTrue(output.truncated());
        assertEquals(LocalCommandRunner.MAX_OUTPUT_CHARS, output.output().length());
        assertTrue(bounded.execute("while :; do :; done", 1).timedOut());
    }

    @Test
    void killsDetachedChildrenOnNormalExitTimeoutAndCancellation() throws Exception {
        Files.writeString(
                workspace().resolve("detached.py"),
                """
                import os, time
                if os.fork() == 0:
                    os.setsid()
                    open('ready', 'w').close()
                    while True:
                        open('heartbeat', 'w').write(str(time.time_ns()))
                        time.sleep(.02)
                else:
                    while not os.path.exists('ready'):
                        time.sleep(.01)
                """);
        var normal = run("/usr/bin/python3 detached.py");
        assertEquals(0, normal.exitCode(), normal.output());
        assertStopped();
        Files.deleteIfExists(workspace().resolve("ready"));
        var timeout = runner(Map.of()).execute("/usr/bin/python3 detached.py; sleep 30", 1);
        assertTrue(timeout.timedOut(), timeout.text());
        assertStopped();
        Files.deleteIfExists(workspace().resolve("ready"));
        var completed = new CompletableFuture<Boolean>();
        var thread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        runner(Map.of())
                                                .execute(
                                                        "/usr/bin/python3 detached.py; sleep 30",
                                                        30);
                                        completed.complete(false);
                                    } catch (InterruptedException cancelled) {
                                        completed.complete(true);
                                    } catch (Exception failure) {
                                        completed.completeExceptionally(failure);
                                    }
                                });
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(workspace().resolve("ready")) && System.nanoTime() < end)
                Thread.sleep(10);
            assertTrue(Files.exists(workspace().resolve("ready")));
            thread.interrupt();
            assertTrue(completed.get(8, TimeUnit.SECONDS));
            assertStopped();
        } finally {
            thread.interrupt();
        }
    }

    private void assertStopped() throws Exception {
        var file = workspace().resolve("heartbeat");
        var content = Files.exists(file) ? Files.readString(file) : "";
        Thread.sleep(150);
        assertEquals(content, Files.exists(file) ? Files.readString(file) : "");
    }

    @Test
    void hostUnixSocketCannotBeUsedEvenWithNetworkGrant() throws Exception {
        var address = java.net.UnixDomainSocketAddress.of(workspace().resolve("host.sock"));
        try (var socket =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            socket.bind(address);
            socket.configureBlocking(false);
            Files.writeString(
                    workspace().resolve("unix.py"),
                    """
                    import socket
                    try:
                        connection = socket.socket(socket.AF_UNIX)
                        connection.connect('host.sock')
                    except PermissionError:
                        pass
                    else:
                        raise AssertionError('host socket reached')
                    """);
            var result = runner(Map.of("network", "allow")).execute("/usr/bin/python3 unix.py", 10);
            assertEquals(0, result.exitCode(), result.output());
            assertNull(socket.accept());
        }
    }

    @Test
    void toolExecutionForPrimaryAndChildActorsUsesTheSameRestrictedBackend() throws Exception {
        var workspace = workspace();
        var file = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
                {"permission":{"bash":"allow"},
                 "agent":{"worker":{"mode":"subagent","permission":{"bash":"allow"}}}}
                """);
        try (var container = io.yupiik.fusion.framework.api.ConfiguringContainer.of().start();
                var mapper = container.lookup(io.yupiik.fusion.json.JsonMapper.class)) {
            var config =
                    fr.openobject.ai.syleks.app.AppConfigFixtures.atWorkspace(
                            mapper.instance(), root, workspace);
            var tools = new fr.openobject.ai.syleks.ai.ToolExecutor(config);
            for (String actor : List.of("build", "worker")) {
                var result =
                        tools.execute(
                                actor,
                                new fr.openobject.ai.syleks.ai.ToolCall(
                                        "call",
                                        "bash",
                                        Map.of(
                                                "command",
                                                "test ! -e '"
                                                        + file
                                                        + "'; printf allowed > "
                                                        + actor)),
                                (operation, target, details) -> true,
                                () -> {});
                assertEquals(
                        fr.openobject.ai.syleks.ai.ToolResult.Status.SUCCESS,
                        result.status(),
                        result.content());
                assertEquals("allowed", Files.readString(workspace.resolve(actor)));
            }
        }
    }

    @Test
    void globalPolicySymlinkIntoWorkspaceFailsBeforeEffects() throws Exception {
        Files.createDirectories(root.resolve("home/.config"));
        Files.createDirectories(workspace().resolve("security"));
        Files.createSymbolicLink(
                root.resolve("home/.config/syleks"), workspace().resolve("security"));
        assertThrows(Exception.class, () -> run("touch prefix"));
        assertFalse(Files.exists(workspace().resolve("prefix")));
    }

    @Test
    void rejectsARealNestedMountBeforeStartingAnyCommand() throws Exception {
        Files.createDirectories(workspace().resolve("nested"));
        var builder =
                new ProcessBuilder(
                        "/usr/bin/unshare",
                        "--user",
                        "--map-root-user",
                        "--mount",
                        "--propagation=private",
                        "--fork",
                        "--kill-child",
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        NestedMountProbe.class.getName(),
                        root.toString());
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("LANG", "C.UTF-8");
        var process = builder.redirectErrorStream(true).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(
                    0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
            assertFalse(Files.exists(workspace().resolve("prefix")));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class NestedMountProbe {
        public static void main(String[] args) throws Exception {
            var root = Path.of(args[0]);
            var workspace = root.resolve("project");
            var mount =
                    new ProcessBuilder(
                                    "/usr/bin/mount",
                                    "-t",
                                    "tmpfs",
                                    "-o",
                                    "size=1m",
                                    "tmpfs",
                                    workspace.resolve("nested").toString())
                            .inheritIO()
                            .start();
            if (mount.waitFor() != 0) throw new AssertionError("Private fixture mount unavailable");
            var runner =
                    new LocalCommandRunner(
                            workspace,
                            SubprocessEnvironment.commands(
                                    Map.of(), Map.of(), root.resolve("home")),
                            CommandSandboxPolicy.parse(Map.of()).backend(root.resolve("home")));
            try {
                runner.execute("touch prefix", 5);
                throw new AssertionError("Nested mount was accepted");
            } catch (java.io.IOException expected) {
                if (!expected.getMessage().contains("nested host mount")) throw expected;
            }
        }
    }

    @Test
    void partialCgroupSupportFailsBeforePayload() throws Exception {
        var marker = root.resolve("prefix");
        var command =
                new ArrayList<>(
                        List.of(
                                "/usr/bin/systemd-run",
                                "--user",
                                "--pipe",
                                "--wait",
                                "--collect",
                                "--quiet",
                                "--expand-environment=no",
                                "--property=RuntimeMaxSec=5",
                                "/usr/bin/env",
                                "-i",
                                "PATH=/usr/bin:/bin",
                                "/bin/bash",
                                "--noprofile",
                                "--norc",
                                "-c",
                                LinuxCommandBackend.GATE,
                                "partial-gate",
                                "67108864",
                                "16",
                                "50",
                                "/usr/bin/touch",
                                marker.toString()));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().clear();
        int uid = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue();
        builder.environment().put("XDG_RUNTIME_DIR", "/run/user/" + uid);
        var process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertFalse(Files.exists(marker));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void failedMountSetupAndHardlinksHaveNoCommandPrefixEffect() throws Exception {
        Files.createSymbolicLink(workspace().resolve(".syleks"), root.resolve("missing"));
        assertThrows(Exception.class, () -> run("touch prefix"));
        assertFalse(Files.exists(workspace().resolve("prefix")));
        Files.delete(workspace().resolve(".syleks"));
        Files.writeString(root.resolve("outside"), "untouched");
        Files.createLink(workspace().resolve("hardlink"), root.resolve("outside"));
        assertThrows(Exception.class, () -> run("touch prefix; printf changed > hardlink"));
        assertFalse(Files.exists(workspace().resolve("prefix")));
        assertEquals("untouched", Files.readString(root.resolve("outside")));
    }
}
