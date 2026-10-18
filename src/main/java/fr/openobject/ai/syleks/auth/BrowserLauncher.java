package fr.openobject.ai.syleks.auth;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@DefaultScoped
public class BrowserLauncher {
    public CompletableFuture<Boolean> open(URI uri) {
        return CompletableFuture.supplyAsync(
                () -> launch(command(uri, System.getProperty("os.name", ""))),
                task -> Thread.ofVirtual().name("browser-launch").start(task));
    }

    static List<String> command(URI uri, String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        // Desktop initializes AWT through JNI, which can abort a native image before any
        // Java exception handler runs. Use the platform opener directly instead.
        if (os.contains("mac")) return List.of("/usr/bin/open", uri.toASCIIString());
        if (os.startsWith("windows"))
            return List.of("rundll32", "url.dll,FileProtocolHandler", uri.toASCIIString());
        return List.of("xdg-open", uri.toASCIIString());
    }

    static boolean launch(List<String> command) {
        Process process = null;
        try {
            process =
                    new ProcessBuilder(command)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) return false;
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | SecurityException e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
