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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCommandRunnerTest {
    @TempDir Path directory;

    // These tests exercise capture/lifecycle in explicit host mode; real isolation has its own
    // suite.
    private static LocalCommandRunner unrestricted(Path directory) {
        return new LocalCommandRunner(
                directory,
                fr.openobject.ai.syleks.app.SubprocessEnvironment.commands(
                        java.util.Map.of(),
                        System.getenv(),
                        Path.of(System.getProperty("user.home"))),
                fr.openobject.ai.syleks.sandbox.CommandExecutionBackend.unrestricted());
    }

    @Test
    void capturesBothStreamsExitStatusAndWorkingDirectory() throws Exception {
        var result =
                unrestricted(directory)
                        .execute("pwd; printf 'output'; printf 'error' >&2; exit 7", 5);
        assertEquals(7, result.exitCode());
        assertTrue(result.output().contains(directory.toString()));
        assertTrue(result.output().contains("outputerror"));
        assertFalse(result.timedOut());
    }

    @Test
    void closesStandardInputAndCapsOutputWithoutBlocking() throws Exception {
        var runner = unrestricted(directory);
        var stdin =
                runner.execute("if read value; then printf unexpected; else printf closed; fi", 5);
        assertEquals("closed", stdin.output());
        var large = runner.execute("printf '%050000d' 1", 5);
        assertEquals(LocalCommandRunner.MAX_OUTPUT_CHARS, large.output().length());
        assertTrue(large.truncated());
        assertEquals(0, large.exitCode());
    }

    @Test
    void ignoresInheritedStartupCodeFunctionsAndShellOptions() throws Exception {
        var startup = directory.resolve("startup.sh");
        Files.writeString(startup, "touch startup-marker\n");
        var impostor = directory.resolve("env");
        Files.writeString(impostor, "#!/bin/sh\ntouch path-marker\n");
        assertTrue(impostor.toFile().setExecutable(true));
        var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ShellChild.class.getName(),
                        directory.toString());
        builder.environment().clear();
        builder.environment().put("HOME", directory.toString());
        builder.environment().put("PATH", ".:" + directory + ":/usr/bin:/bin");
        builder.environment().put("LANG", "C.UTF-8");
        for (var key :
                java.util.List.of(
                        "OPENAI_API_KEY",
                        "AWS_SECRET_ACCESS_KEY",
                        "SSH_AUTH_SOCK",
                        "GPG_AGENT_INFO",
                        "PYTHONPATH",
                        "PYTHONSTARTUP",
                        "NODE_OPTIONS",
                        "PERL5OPT",
                        "RUBYOPT",
                        "LD_LIBRARY_PATH",
                        "DYLD_INSERT_LIBRARIES"))
            builder.environment().put(key, "SEC02_FAKE_SECRET");
        builder.environment().put("LD_PRELOAD", "");
        builder.environment().put("JAVA_TOOL_OPTIONS", "-Dsec02.fake=true");
        builder.environment().put("JDK_JAVA_OPTIONS", "-Dsec02.fake=true");
        builder.environment().put("_JAVA_OPTIONS", "-Dsec02.fake=true");
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("BASH_ENV", startup.toString());
        builder.environment().put("ENV", startup.toString());
        builder.environment().put("BASH_FUNC_printf%%", "() { touch function-marker; }");
        builder.environment().put("SHELLOPTS", "xtrace");
        builder.environment().put("BASHOPTS", "expand_aliases");
        builder.environment().put("SYLEKS_SHELL_TEST_VALUE", "ordinary-environment");
        var process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals(
                    "isolated",
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(Files.exists(directory.resolve("startup-marker")));
            assertFalse(Files.exists(directory.resolve("function-marker")));
            assertFalse(Files.exists(directory.resolve("path-marker")));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class ShellChild {
        public static void main(String[] args) throws Exception {
            var policy =
                    fr.openobject.ai.syleks.app.SubprocessEnvironment.commands(
                            java.util.Map.of("pass_env", java.util.List.of("JAVA_HOME")),
                            System.getenv(),
                            Path.of(args[0]));
            var runner =
                    new LocalCommandRunner(
                            Path.of(args[0]),
                            policy,
                            fr.openobject.ai.syleks.sandbox.CommandExecutionBackend.unrestricted());
            var result = runner.execute("env", 5);
            if (result.output().contains("SEC02_FAKE_SECRET")
                    || result.output().contains("SYLEKS_SHELL_TEST_VALUE=")
                    || result.output().contains("BASH_FUNC_")
                    || result.output().contains("LD_PRELOAD=")
                    || result.output().contains("JAVA_TOOL_OPTIONS=")
                    || result.output().contains("JDK_JAVA_OPTIONS=")
                    || result.output().contains("_JAVA_OPTIONS=")
                    || result.output().contains("BASH_ENV=")
                    || result.output().contains("SHELLOPTS=")
                    || !result.output().contains("JAVA_HOME=")
                    || !result.output().contains("LANG=C.UTF-8")) System.exit(2);
            result = runner.execute("printf isolated", 5);
            System.out.print(result.output());
            if (result.exitCode() != 0) System.exit(1);
        }
    }

    @Test
    void timesOutAndTerminatesTheCommandTree() throws Exception {
        var pidFile = directory.resolve("child.pid");
        var result =
                unrestricted(directory)
                        .execute(
                                "sleep 30 & child=$!; printf '%s' \"$child\" > child.pid; wait", 1);
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        // Bash can append a job-termination diagnostic to the merged output during cleanup.
        long child = Long.parseLong(Files.readString(pidFile).strip());
        awaitDead(child);
    }

    @Test
    void cancelsTheProcessWhenItsWorkerIsInterrupted() throws Exception {
        var pidFile = directory.resolve("child.pid");
        var done = new CompletableFuture<Boolean>();
        var thread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        unrestricted(directory)
                                                .execute(
                                                        "sleep 30 & child=$!; printf '%s' \"$child\" > child.pid; wait",
                                                        30);
                                        done.complete(false);
                                    } catch (InterruptedException e) {
                                        done.complete(true);
                                    } catch (Exception e) {
                                        done.completeExceptionally(e);
                                    }
                                });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0)
                    && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(Files.exists(pidFile));
            long child = Long.parseLong(Files.readString(pidFile));
            thread.interrupt();
            assertTrue(done.get(3, TimeUnit.SECONDS));
            awaitDead(child);
        } finally {
            thread.interrupt();
        }
    }

    private void awaitDead(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
