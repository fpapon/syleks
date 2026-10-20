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

    @Test
    void capturesBothStreamsExitStatusAndWorkingDirectory() throws Exception {
        var result =
                new LocalCommandRunner(directory)
                        .execute("pwd; printf 'output'; printf 'error' >&2; exit 7", 5);
        assertEquals(7, result.exitCode());
        assertTrue(result.output().contains(directory.toString()));
        assertTrue(result.output().contains("outputerror"));
        assertFalse(result.timedOut());
    }

    @Test
    void closesStandardInputAndCapsOutputWithoutBlocking() throws Exception {
        var runner = new LocalCommandRunner(directory);
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
        var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ShellChild.class.getName(),
                        directory.toString());
        builder.environment().put("BASH_ENV", startup.toString());
        builder.environment().put("ENV", startup.toString());
        builder.environment().put("BASH_FUNC_printf%%", "() { touch function-marker; }");
        builder.environment().put("SHELLOPTS", "xtrace");
        builder.environment().put("BASHOPTS", "expand_aliases");
        builder.environment().put("SYLEKS_SHELL_TEST_VALUE", "ordinary-environment");
        var process = builder.redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals(
                    "ordinary-environment",
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(Files.exists(directory.resolve("startup-marker")));
            assertFalse(Files.exists(directory.resolve("function-marker")));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class ShellChild {
        public static void main(String[] args) throws Exception {
            var result =
                    new LocalCommandRunner(Path.of(args[0]))
                            .execute("printf '%s' \"$SYLEKS_SHELL_TEST_VALUE\"", 5);
            System.out.print(result.output());
            if (result.exitCode() != 0) System.exit(1);
        }
    }

    @Test
    void timesOutAndTerminatesTheCommandTree() throws Exception {
        var result =
                new LocalCommandRunner(directory)
                        .execute("sleep 30 & child=$!; printf '%s' \"$child\"; wait", 1);
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        long child = Long.parseLong(result.output().strip());
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
                                        new LocalCommandRunner(directory)
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
