package fr.openobject.ai.syleks.app;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.auth.*;
import fr.openobject.ai.syleks.provider.ProviderFixture;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderCliTest {
    @TempDir Path root;

    @Test
    void fusionBootstrapsProviderCommandsWithoutATerminalAndPersistsExplicitSelections()
            throws Exception {
        assertTrue(run("provider", "list").contains("chatgpt\tChatGPT"));
        assertTrue(run("auth", "status").contains("Active provider: ollama"));
        assertTrue(
                run("model", "use", "ollama/offline-model")
                        .contains("Selected ollama/offline-model"));
        assertTrue(run("auth", "status").contains("ollama/offline-model"));
        assertTrue(run("auth", "accounts").contains("Active provider: ollama"));
        assertTrue(run("--help").contains("syleks auth"));
        assertFalse(Files.exists(root.resolve("config/syleks/auth/accounts")));
    }

    private String run(String... args) throws Exception {
        Files.createDirectories(root.resolve("home"));
        var command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-Duser.home=" + root.resolve("home"),
                                "-cp",
                                System.getProperty("java.class.path"),
                                Main.class.getName()));
        command.addAll(Arrays.asList(args));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("HOME", root.resolve("home").toString());
        builder.environment().put("XDG_CONFIG_HOME", root.resolve("config").toString());
        builder.environment().put("SYLEKS_CREDENTIAL_BACKEND", "file");
        for (String name : List.of("OLLAMA_URL", "OLLAMA_MODEL", "OPENAI_API_KEY"))
            builder.environment().remove(name);
        var process = builder.start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String text =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), text);
        return text;
    }

    @Test
    void cliLoginUsesTheSameOAuthAndNeverPrintsTokensOrIdentityHintUrls() throws Exception {
        try (var f = new AuthFixture(root)) {
            var p = new ProviderFixture(f);
            var browser =
                    new BrowserLauncher() {
                        public CompletableFuture<Boolean> open(URI uri) {
                            return CompletableFuture.supplyAsync(
                                    () -> {
                                        try {
                                            var q = ChatGptOAuthService.query(uri.getRawQuery());
                                            f.nonce.set(q.get("nonce"));
                                            var callback =
                                                    URI.create(
                                                            q.get("redirect_uri")
                                                                    + "?"
                                                                    + OAuthHttp.form(
                                                                            Map.of(
                                                                                    "state",
                                                                                    q.get("state"),
                                                                                    "code",
                                                                                    "code-secret",
                                                                                    "client_id",
                                                                                    "oaiapp_test")));
                                            java.net.http.HttpClient.newHttpClient()
                                                    .send(
                                                            java.net.http.HttpRequest.newBuilder(
                                                                            callback)
                                                                    .GET()
                                                                    .build(),
                                                            java.net.http.HttpResponse.BodyHandlers
                                                                    .discarding());
                                            return true;
                                        } catch (Exception e) {
                                            throw new CompletionException(e);
                                        }
                                    });
                        }
                    };
            var output = new ByteArrayOutputStream();
            var out = new PrintStream(output);
            var commands = new ProviderCommands(p.service, f.auth, browser);
            commands.run(new String[] {"auth", "login", "chatgpt"}, out);
            var account = f.auth.accounts().getFirst();
            commands.run(new String[] {"auth", "login", "chatgpt", account.account().id()}, out);
            commands.run(new String[] {"model", "list"}, out);
            String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("Plan usage: Authorized"));
            assertTrue(text.contains("chatgpt/test-model"));
            for (String secret :
                    List.of(
                            account.accessToken(),
                            account.refreshToken(),
                            account.idToken(),
                            "code-secret",
                            "id_token_hint")) assertFalse(text.contains(secret));
            assertEquals(1, f.auth.accounts().size());
        }
    }
}
