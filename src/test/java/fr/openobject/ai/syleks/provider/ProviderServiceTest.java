package fr.openobject.ai.syleks.provider;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.auth.AuthFixture;
import fr.openobject.ai.syleks.session.SessionFixture;
import fr.openobject.ai.syleks.session.model.MessageStatus;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderServiceTest {
    @TempDir Path root;

    @Test
    void discoversProvidersPersistsSelectionsAndKeepsSeparateAccounts() throws Exception {
        try (var f = new AuthFixture(root)) {
            var p = new ProviderFixture(f);
            assertEquals(
                    List.of(
                            "chatgpt",
                            "ollama",
                            "openai-api",
                            "spacexsi",
                            "openrouter",
                            "opencode",
                            "google"),
                    p.registry.providers().stream().map(LlmProvider::id).toList());
            assertEquals("ollama", p.service.active().providerId());
            p.service.useModel("ollama/local-model");
            var first = f.login(null);
            var second = f.login(null);
            p.service.useAccount(first.account().id());
            assertEquals("chatgpt/test-model", p.service.selection());
            p.service.useAccount(second.account().id());
            assertEquals(second.account().id(), p.service.active().accountId());
            assertEquals(
                    second.account().id(), new ProviderFixture(f).service.active().accountId());
            p.service.restore("chatgpt/test-model", first.account().id());
            assertEquals(first.account().id(), p.service.active().accountId());
            assertThrows(
                    Exception.class,
                    () -> p.service.restore("chatgpt/test-model", UUID.randomUUID().toString()));
            assertThrows(Exception.class, () -> p.service.useModel("chatgpt/missing"));
            assertThrows(Exception.class, () -> p.service.useProvider("missing"));
            p.service.useModel("ollama/local-model");
            assertEquals("Local", p.service.connectionStatus(p.service.selection()));
            assertNull(p.service.active().accountId());
            assertEquals(2, f.auth.accounts().size());
        }
    }

    @Test
    void projectPolicyBlocksCloudRequestsAndModelDiscoveryWithoutBreakingLocalSelection()
            throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var p = new ProviderFixture(f);
            Files.createDirectories(f.config.workspace().resolve(".syleks"));
            Files.writeString(
                    f.config.workspace().resolve(".syleks/project.json"),
                    "{\"allow_cloud\":false}");
            assertThrows(Exception.class, () -> p.service.useAccount(c.account().id()));
            assertThrows(Exception.class, () -> p.service.checkCloud("openai-api"));
            assertThrows(Exception.class, () -> p.service.checkCloud("spacexsi"));
            assertThrows(Exception.class, () -> p.service.checkCloud("openrouter"));
            assertThrows(Exception.class, () -> p.service.checkCloud("opencode"));
            assertThrows(Exception.class, () -> p.service.checkCloud("google"));
            p.service.useModel("ollama/local");
            assertEquals("ollama/local", p.service.selection());
        }
    }

    @Test
    void persistsProviderAccountHistoryPartialFailuresAndResumesWithTheOriginalAccount()
            throws Exception {
        String id, account;
        try (var f = new AuthFixture(root);
                var database = new SessionFixture(root.resolve("sessions"))) {
            var c = f.login(null);
            account = c.account().id();
            var p = new ProviderFixture(f);
            p.service.useAccount(account);
            database.agent.providers(p.service);
            var chat =
                    new ChatSession(
                            new OllamaClient(database.config, database.mapper.instance()),
                            database.config);
            chat.persistence(database.agent);
            chat.providers(p.service);
            chat.initializeProviders();
            chat.sessionAction("new", "");
            id = chat.snapshot().sessionId();
            assertTrue(chat.send("first question"));
            await(chat);
            assertEquals(account, database.sessions.load(id).accountId());
            assertEquals("chatgpt", database.sessions.load(id).provider());
            assertTrue(
                    database.conversation.history(id).stream()
                            .anyMatch(m -> m.content().contains("hello")));
            f.responsesHandler.set(
                    ex ->
                            AuthFixture.respond(
                                    ex,
                                    200,
                                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial output\"}\n\n"));
            assertTrue(chat.send("second question"));
            await(chat);
            assertEquals(
                    MessageStatus.ERROR, database.conversation.messages(id).getLast().status());
            assertTrue(
                    database.conversation
                            .history(id)
                            .getLast()
                            .content()
                            .contains("partial output"));
            assertFalse(database.sessions.export(id).contains(c.accessToken()));
            assertFalse(database.sessions.export(id).contains(c.refreshToken()));
            assertEquals(account, database.sessions.fork(id).accountId());
            chat.shutdown();
        }
        try (var database = new SessionFixture(root.resolve("sessions"));
                var f = new AuthFixture(root)) {
            var p = new ProviderFixture(f);
            database.agent.providers(p.service);
            var chat =
                    new ChatSession(
                            new OllamaClient(database.config, database.mapper.instance()),
                            database.config);
            chat.persistence(database.agent);
            chat.providers(p.service);
            chat.sessionAction("open", id);
            assertEquals(account, p.service.active().accountId());
            assertEquals("chatgpt/test-model", chat.snapshot().model());
            assertTrue(
                    chat.history().stream().anyMatch(m -> m.content().contains("first question")));
            chat.model("ollama/replacement");
            assertEquals(id, chat.snapshot().sessionId());
            assertNull(database.sessions.load(id).accountId());
            assertEquals("ollama", database.sessions.load(id).provider());
            assertTrue(
                    chat.history().stream().anyMatch(m -> m.content().contains("first question")));
            chat.shutdown();
        }
    }

    private static void await(ChatSession chat) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (chat.snapshot().busy() && System.nanoTime() < end) Thread.sleep(10);
        assertFalse(chat.snapshot().busy(), chat.snapshot().status());
    }
}
