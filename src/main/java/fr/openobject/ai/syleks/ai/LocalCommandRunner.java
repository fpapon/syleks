package fr.openobject.ai.syleks.ai;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Captures local process output without giving the process the TUI's input or output streams. */
public final class LocalCommandRunner {
    public static final int MAX_OUTPUT_CHARS = 32000;
    private final Path directory;

    public LocalCommandRunner(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    public record Result(int exitCode, String output, boolean timedOut, boolean truncated) {
        public String text() {
            return "Exit code: " + exitCode + (timedOut ? " (timed out)" : "") + (truncated ? " (output truncated)" : "") + "\n" + output;
        }
    }

    public Result execute(String command, int timeoutSeconds) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Command cancelled");
        if (command == null || command.isBlank() || command.indexOf(0) >= 0 || command.length() > 12000) {
            throw new IllegalArgumentException("command must be a non-blank string of at most 12000 characters without NUL bytes");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) throw new IllegalArgumentException("timeout_seconds must be an integer from 1 to 300");
        var process = new ProcessBuilder("/bin/bash", "-c", command).directory(directory.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var output = new CompletableFuture<String>();
        var truncated = new java.util.concurrent.atomic.AtomicBoolean();
        var reader = Thread.ofVirtual().name("command-output").start(() -> {
            try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                var text = new StringBuilder();
                var buffer = new char[4096];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    int keep = Math.min(count, MAX_OUTPUT_CHARS - text.length());
                    if (keep > 0) text.append(buffer, 0, keep);
                    if (keep < count) truncated.set(true);
                }
                if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) text.setLength(text.length() - 1);
                output.complete(text.toString());
            } catch (Exception e) {
                output.completeExceptionally(e);
            }
        });
        boolean timedOut = false;
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                timedOut = true;
                terminate(process);
            }
            String text;
            try {
                text = output.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                terminate(process);
                process.getInputStream().close();
                text = "Output stream remained open after command completion.";
            }
            return new Result(timedOut ? -1 : process.exitValue(), text, timedOut, truncated.get());
        } finally {
            terminate(process);
            try { process.getInputStream().close(); } catch (java.io.IOException ignored) { /* cleanup */ }
            reader.interrupt();
        }
    }

    private static void terminate(Process process) {
        var descendants = process.descendants().toList();
        for (var child : descendants.reversed()) child.destroyForcibly();
        if (process.isAlive()) process.destroyForcibly();
    }
}
