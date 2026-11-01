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

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingTest {
    @TempDir Path root;

    @Test
    void selectsYupiikWithoutWritingToTheTerminalByDefault() throws Exception {
        var result = launch();
        assertEquals("io.yupiik.logging.jul.YupiikLogManager", result.strip());
    }

    @Test
    void routesDiagnosticsToConfiguredFileWithoutLoggingArguments() throws Exception {
        var log = root.resolve("diagnostics.log");
        var config = root.resolve("logging.properties");
        Files.writeString(
                config,
                """
                .handlers=file
                .level=FINE
                io.yupiik.logging.jul.handler.LocalFileHandler.noRotation=true
                io.yupiik.logging.jul.handler.LocalFileHandler.filenamePattern=%s
                """
                        .formatted(log.toString().replace("\\", "/")));
        assertEquals(
                "io.yupiik.logging.jul.YupiikLogManager",
                launch("-Djava.util.logging.config.file=" + config).strip());
        var diagnostic = Files.readString(log);
        assertTrue(
                diagnostic.contains("Command rejected: java.lang.IllegalArgumentException"),
                diagnostic);
        assertFalse(diagnostic.contains("private-command-argument"), diagnostic);
    }

    @Test
    void preservesExplicitLogManager() throws Exception {
        assertEquals(
                "java.util.logging.LogManager",
                launch("-Djava.util.logging.manager=java.util.logging.LogManager").strip());
    }

    private String launch(String... properties) throws Exception {
        var command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java")
                                        .toString()));
        command.addAll(List.of(properties));
        command.addAll(
                List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName()));
        var builder = new ProcessBuilder(command).directory(root.toFile());
        builder.environment().remove("JAVA_UTIL_LOGGING_CONFIG_FILE");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Logging probe timed out");
            var error =
                    new String(
                            process.getErrorStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), error);
            assertEquals("", error);
            return new String(
                    process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            try (var sink = new PrintStream(OutputStream.nullOutputStream())) {
                if (Main.run(new String[] {"--private-command-argument"}, sink, sink) != 2)
                    throw new AssertionError("Expected invalid arguments");
            }
            if (LogManager.getLogManager()
                    .getClass()
                    .getName()
                    .equals("io.yupiik.logging.jul.YupiikLogManager"))
                java.util.logging.Logger.getLogger(Probe.class.getName())
                        .warning("Diagnostic probe");
            System.out.println(LogManager.getLogManager().getClass().getName());
        }
    }
}
