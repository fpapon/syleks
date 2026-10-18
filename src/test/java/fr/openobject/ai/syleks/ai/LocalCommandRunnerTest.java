package fr.openobject.ai.syleks.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LocalCommandRunnerTest {
    @TempDir
    Path directory;

    @Test
    void capturesBothStreamsExitStatusAndWorkingDirectory() throws Exception {
        var result = new LocalCommandRunner(directory).execute("pwd; printf 'output'; printf 'error' >&2; exit 7", 5);
        assertEquals(7, result.exitCode());
        assertTrue(result.output().contains(directory.toString()));
        assertTrue(result.output().contains("outputerror"));
        assertFalse(result.timedOut());
    }

    @Test
    void closesStandardInputAndCapsOutputWithoutBlocking() throws Exception {
        var runner = new LocalCommandRunner(directory);
        var stdin = runner.execute("if read value; then printf unexpected; else printf closed; fi", 5);
        assertEquals("closed", stdin.output());
        var large = runner.execute("printf '%050000d' 1", 5);
        assertEquals(LocalCommandRunner.MAX_OUTPUT_CHARS, large.output().length());
        assertTrue(large.truncated());
        assertEquals(0, large.exitCode());
    }

    @Test
    void timesOutAndTerminatesTheCommandTree() throws Exception {
        var result = new LocalCommandRunner(directory).execute("sleep 30 & child=$!; printf '%s' \"$child\"; wait", 1);
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        long child = Long.parseLong(result.output().strip());
        awaitDead(child);
    }

    @Test
    void cancelsTheProcessWhenItsWorkerIsInterrupted() throws Exception {
        var pidFile = directory.resolve("child.pid");
        var done = new CompletableFuture<Boolean>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                new LocalCommandRunner(directory).execute("sleep 30 & child=$!; printf '%s' \"$child\" > child.pid; wait", 30);
                done.complete(false);
            } catch (InterruptedException e) {
                done.complete(true);
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) Thread.sleep(5);
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
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
