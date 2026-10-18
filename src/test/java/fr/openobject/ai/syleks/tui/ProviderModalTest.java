package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.app.*;
import fr.openobject.ai.syleks.auth.*;
import fr.openobject.ai.syleks.provider.ProviderFixture;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderModalTest {
    @TempDir Path root;

    @Test
    void modalSupportsKeyboardFilteringResizingAndNeverPaintsApiKeys() {
        var modal =
                new ProviderModal(
                        ProviderModal.Kind.CONNECT,
                        "Connect to a provider",
                        List.of(
                                new ProviderModal.Entry(
                                        "chatgpt", "ChatGPT", "Continue with ChatGPT"),
                                new ProviderModal.Entry("ollama", "Ollama", "Local provider")));
        assertEquals("chatgpt", modal.selected().id());
        modal.move(1);
        assertEquals("ollama", modal.selected().id());
        modal.type('g');
        assertEquals("chatgpt", modal.selected().id());
        modal.backspace();
        assertEquals(2, modal.filtered().size());
        for (int[] size : List.of(new int[] {1, 1}, new int[] {35, 15}, new int[] {120, 40}))
            assertNotNull(modal.paint(new TerminalCanvas(size[0], size[1])));
        var secret = new ProviderModal(ProviderModal.Kind.API_KEY, "OpenAI API", List.of());
        for (int cp : "secret-api-key".codePoints().toArray()) secret.type(cp);
        var canvas = secret.paint(new TerminalCanvas(100, 30)).canvas();
        StringBuilder screen = new StringBuilder();
        for (int y = 0; y < canvas.height(); y++)
            for (int x = 0; x < canvas.width(); x++) screen.append(canvas.at(x, y).glyph());
        assertFalse(screen.toString().contains("secret-api-key"));
        assertTrue(screen.toString().contains("•••"));
    }

    @Test
    void connectStartsOAuthReportsConnectedSelectsModelAndLogoutPreservesConversation()
            throws Exception {
        try (var f = new AuthFixture(root)) {
            var p = new ProviderFixture(f);
            var chat = new ChatSession(new OllamaClient(f.config, f.json), f.config);
            chat.providers(p.service);
            var before = chat.history();
            try (var app =
                    new TerminalApp(chat, new ConfigInitializer(), new ConfigInitWizard(f.json))) {
                app.authentication = f.auth;
                app.browser =
                        new BrowserLauncher() {
                            public CompletableFuture<Boolean> open(URI uri) {
                                return CompletableFuture.supplyAsync(
                                        () -> {
                                            try {
                                                var q =
                                                        ChatGptOAuthService.query(
                                                                uri.getRawQuery());
                                                f.nonce.set(q.get("nonce"));
                                                URI callback =
                                                        URI.create(
                                                                q.get("redirect_uri")
                                                                        + "?"
                                                                        + OAuthHttp.form(
                                                                                Map.of(
                                                                                        "state",
                                                                                        q.get(
                                                                                                "state"),
                                                                                        "code",
                                                                                        "test-code",
                                                                                        "client_id",
                                                                                        "oaiapp_test")));
                                                java.net.http.HttpClient.newHttpClient()
                                                        .send(
                                                                java.net.http.HttpRequest
                                                                        .newBuilder(callback)
                                                                        .GET()
                                                                        .build(),
                                                                java.net.http.HttpResponse
                                                                        .BodyHandlers.discarding());
                                                return true;
                                            } catch (Exception e) {
                                                throw new CompletionException(e);
                                            }
                                        });
                            }
                        };
                type(app, "/connect\r");
                assertEquals(ProviderModal.Kind.CONNECT, app.providerModal().kind());
                app.handle('\r', null);
                assertEquals(ProviderModal.Kind.AUTH, app.providerModal().kind());
                await(
                        app,
                        () ->
                                app.providerModal() != null
                                        && app.providerModal().kind()
                                                == ProviderModal.Kind.MESSAGE);
                assertTrue(
                        app.providerModal().message().contains("Plan usage: Authorized"),
                        app.providerModal().message());
                assertTrue(chat.snapshot().providerName().contains("Connected"));
                assertEquals("chatgpt/test-model", chat.snapshot().model());
                assertTrue(
                        chat.history().stream()
                                .noneMatch(
                                        m ->
                                                m.content().contains("id_token_hint")
                                                        || m.content().contains("test-access")));
                app.handle('\r', null);
                type(app, "/models\r");
                await(app, () -> app.providerModal().kind() == ProviderModal.Kind.MODELS);
                assertEquals("chatgpt/test-model", app.providerModal().selected().id());
                app.handle('\r', null);
                await(
                        app,
                        () ->
                                app.providerModal().kind() == ProviderModal.Kind.MESSAGE
                                        && app.providerModal().message().startsWith("Selected"));
                app.handle('\r', null);
                type(app, "/accounts\r");
                await(app, () -> app.providerModal().kind() == ProviderModal.Kind.ACCOUNTS);
                assertEquals(2, app.providerModal().filtered().size());
                assertTrue(app.providerModal().filtered().getFirst().label().startsWith("● "));
                app.handle(3, null);
                assertNull(app.providerModal());
                // Explicit API-key logout avoids all real network endpoints in this test.
                var key = f.auth.apiKey("fake-key");
                p.service.restore("openai-api/model", key.account().id());
                type(app, "/logout\r");
                await(
                        app,
                        () ->
                                app.providerModal().kind() == ProviderModal.Kind.MESSAGE
                                        && app.providerModal().message().startsWith("Signed out"));
                assertFalse(f.store.load(key.account().id()).connected());
                assertTrue(chat.history().containsAll(before));
            }
        }
    }

    @Test
    void cancellingAuthenticationDoesNotSaveCredentialsOrLoseConversation() throws Exception {
        try (var f = new AuthFixture(root)) {
            var chat = new ChatSession(new OllamaClient(f.config, f.json), f.config);
            chat.providers(new ProviderFixture(f).service);
            try (var app =
                    new TerminalApp(chat, new ConfigInitializer(), new ConfigInitWizard(f.json))) {
                app.authentication = f.auth;
                app.browser =
                        new BrowserLauncher() {
                            public CompletableFuture<Boolean> open(URI uri) {
                                return CompletableFuture.completedFuture(false);
                            }
                        };
                var history = chat.history();
                type(app, "/connect\r");
                app.handle('\r', null);
                await(app, () -> app.providerModal().message().contains("Browser unavailable"));
                app.handle(3, null);
                assertNull(app.providerModal());
                assertEquals(history, chat.history());
                assertTrue(f.auth.accounts().isEmpty());
            }
        }
    }

    private static void type(TerminalApp app, String text) throws Exception {
        for (int cp : text.codePoints().toArray()) app.handle(cp, null);
    }

    private static void await(TerminalApp app, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            app.drainCompletions();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Provider UI did not reach expected state");
    }
}
