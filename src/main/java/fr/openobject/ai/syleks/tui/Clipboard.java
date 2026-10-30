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
package fr.openobject.ai.syleks.tui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class Clipboard {
    private Clipboard() {}

    static boolean copyDesktop(String text) {
        for (var command : commands(System.getProperty("os.name", ""), System.getenv())) {
            if (copyCommand(command, text)) return true;
        }
        return copyAwt(text, System.getProperty("org.graalvm.nativeimage.imagecode") != null);
    }

    static List<List<String>> commands(String os, Map<String, String> environment) {
        if (os.startsWith("Mac")) return List.of(List.of("pbcopy"));
        if (os.startsWith("Windows")) return List.of(List.of("clip.exe"));
        var commands = new java.util.ArrayList<List<String>>();
        if (environment.containsKey("WAYLAND_DISPLAY"))
            commands.add(List.of("wl-copy", "--type", "text/plain;charset=utf-8"));
        if (environment.containsKey("DISPLAY")) {
            commands.add(List.of("xclip", "-selection", "clipboard", "-in"));
            commands.add(List.of("xsel", "--clipboard", "--input"));
        }
        return List.copyOf(commands);
    }

    static boolean copyCommand(List<String> command, String text) {
        Process process = null;
        try {
            // Resolve helpers explicitly: never pass copied text to a shell or command arguments.
            var executable = executable(command.getFirst());
            if (executable == null) return false;
            var args = new java.util.ArrayList<>(command);
            args.set(0, executable.toString());
            process =
                    new ProcessBuilder(args)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            var started = process;
            var written = new java.util.concurrent.CompletableFuture<Boolean>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (var input = started.getOutputStream()) {
                                    input.write(text.getBytes(StandardCharsets.UTF_8));
                                    written.complete(true);
                                } catch (IOException e) {
                                    written.complete(false);
                                }
                            });
            if (!process.waitFor(3, TimeUnit.SECONDS)) return false;
            return process.exitValue() == 0 && written.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException
                | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException
                | RuntimeException unavailable) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static Path executable(String name) {
        var path = Path.of(name);
        if (path.isAbsolute()) return Files.isExecutable(path) ? path : null;
        for (var directory :
                System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            var candidate = Path.of(directory).resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    static boolean copyAwt(String text, boolean nativeImage) {
        // AWT's JNI initialization aborts native builds before Java can catch the failure.
        if (nativeImage) return false;
        try {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            return true;
        } catch (RuntimeException | LinkageError | java.awt.AWTError unavailable) {
            return false;
        }
    }

    static String terminalSequence(String text) {
        return "\u001b]52;c;"
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
                + "\u0007";
    }
}
