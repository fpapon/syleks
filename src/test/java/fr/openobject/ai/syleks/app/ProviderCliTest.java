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

    @Test
    void cliEncryptsProviderKeyInGlobalConfigurationAndPreservesCiphertextOnRepeat()
            throws Exception {
        Path file = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
                {"provider":{"custom":{"options":{"baseURL":"https://provider.example/v1","apiKey":"synthetic-cli-secret"}}}}
                """);
        assertTrue(run("secrets", "encrypt").contains("Encrypted 1 values"));
        String encrypted = Files.readString(file);
        assertTrue(encrypted.contains("syleks-encrypted:v1:"));
        assertFalse(encrypted.contains("synthetic-cli-secret"));
        assertTrue(run("secrets", "encrypt").contains("Encrypted 0 values"));
        assertEquals(encrypted, Files.readString(file));
        assertTrue(run("secrets", "migrate").contains("Migrated 0 values"));
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
        builder.environment()
                .put("SYLEKS_CONFIG_KEY", Base64.getEncoder().encodeToString(new byte[32]));
        for (String name :
                List.of(
                        "OLLAMA_URL",
                        "OLLAMA_MODEL",
                        "OPENAI_API_KEY",
                        "XAI_API_KEY",
                        "OPENROUTER_API_KEY",
                        "OPENCODE_API_KEY",
                        "GEMINI_API_KEY")) builder.environment().remove(name);
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
    void apiKeyLoginReadsOnlyTheMatchingVariable() {
        assertEquals("XAI_API_KEY", ProviderCommands.apiKeyVariable("spacexsi"));
        assertEquals("OPENROUTER_API_KEY", ProviderCommands.apiKeyVariable("openrouter"));
        assertEquals("OPENCODE_API_KEY", ProviderCommands.apiKeyVariable("opencode"));
        assertEquals("GEMINI_API_KEY", ProviderCommands.apiKeyVariable("google"));
        assertEquals("OPENAI_API_KEY", ProviderCommands.apiKeyVariable("openai-api"));
        assertNull(ProviderCommands.apiKeyVariable("chatgpt"));
        var environment = Map.of("XAI_API_KEY", "xai-secret", "OPENAI_API_KEY", "openai-secret");
        assertEquals("xai-secret", ProviderCommands.loginKey("spacexsi", environment));
        assertEquals("openai-secret", ProviderCommands.loginKey("openai-api", environment));
        assertNull(ProviderCommands.loginKey("openrouter", environment));
        assertNull(ProviderCommands.loginKey("google", environment));
        assertEquals(
                "gemini-secret",
                ProviderCommands.loginKey("google", Map.of("GEMINI_API_KEY", "gemini-secret")));
        assertNull(ProviderCommands.loginKey("ollama", environment));
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
