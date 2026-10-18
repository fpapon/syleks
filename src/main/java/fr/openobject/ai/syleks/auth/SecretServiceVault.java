package fr.openobject.ai.syleks.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Linux libsecret adapter. Secrets travel through stdin/stdout, never process arguments. */
final class SecretServiceVault implements CredentialVault {
    private static final Path COMMAND = Path.of("/usr/bin/secret-tool");

    static CredentialVault detect() {
        String backend = System.getenv().getOrDefault("SYLEKS_CREDENTIAL_BACKEND", "auto");
        if (!Set.of("auto", "file", "secret-service").contains(backend))
            throw new IllegalArgumentException(
                    "SYLEKS_CREDENTIAL_BACKEND must be auto, file or secret-service");
        if (backend.equals("file")) return null;
        boolean available =
                Files.isExecutable(COMMAND) && System.getenv("DBUS_SESSION_BUS_ADDRESS") != null;
        if (backend.equals("secret-service") && !available)
            throw new IllegalStateException(
                    "Secret Service unavailable; install libsecret-tools and unlock the user keyring, or explicitly select the protected file backend");
        return available ? new SecretServiceVault() : null;
    }

    private List<String> arguments(String operation, String reference) {
        UUID.fromString(reference);
        var args = new ArrayList<String>();
        args.add(COMMAND.toString());
        args.add(operation);
        if (operation.equals("store")) args.add("--label=Syleks credentials");
        args.addAll(List.of("application", "fr.openobject.syleks", "credential", reference));
        return args;
    }

    public void put(String reference, String secret) throws IOException {
        run(arguments("store", reference), secret);
    }

    public String get(String reference) throws IOException {
        return run(arguments("lookup", reference), null);
    }

    public void delete(String reference) throws IOException {
        run(arguments("clear", reference), null);
    }

    private String run(List<String> args, String input) throws IOException {
        Process process =
                new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            var output =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return process.getInputStream().readNBytes(1024 * 1024);
                                } catch (IOException e) {
                                    throw new CompletionException(e);
                                }
                            },
                            task -> Thread.ofVirtual().start(task));
            try (var stdin = process.getOutputStream()) {
                if (input != null) stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("System keyring unavailable or locked; unlock it and retry");
            return new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Keyring operation cancelled");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Keyring operation failed");
        } finally {
            process.destroyForcibly();
        }
    }
}
