package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.session.model.*;
import fr.openobject.ai.syleks.session.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlitePersistenceTest {
    @TempDir Path root;

    @Test void initializesMigratesAndReopensWithConfiguredPragmas() throws Exception {
        try (var f = new SessionFixture(root)) {
            f.database.read(c -> {
                for (var item : Map.of("user_version", "1", "journal_mode", "wal", "foreign_keys", "1", "busy_timeout", "5000").entrySet()) {
                    try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA " + item.getKey())) { assertTrue(r.next()); assertEquals(item.getValue(), r.getString(1)); }
                }
                return null;
            });
            assertEquals(root.resolve("project").toRealPath().toString(), f.projects.current().rootPath());
            f.sessions.create("qwen3:8b", "Ollama");
        }
        try (var f = new SessionFixture(root)) { assertEquals(1, f.sessions.list().size()); }
    }

    @Test void rejectsFutureSchemaWithoutChangingExistingData() throws Exception {
        try (var f = new SessionFixture(root)) {
            f.sessions.create("qwen3:8b", "Ollama");
            f.database.write(c -> { try (var s = c.createStatement()) { s.execute("PRAGMA user_version=99"); } return null; });
            assertTrue(assertThrows(IllegalStateException.class, () -> new SqliteDatabase(f.paths)).getMessage().contains("newer"));
            assertEquals(1, f.repositories.listSessions(f.projects.currentId()).size());
        }
    }

    @Test void failedMigrationRollsBackAllSchemaChangesAndPreservesExistingTables() throws Exception {
        var paths = DataPaths.configured(root, Map.of("SYLEKS_DATA_DIR", root.resolve("data").toString()));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = c.createStatement()) {
            statement.execute("CREATE TABLE sessions(keep TEXT)"); statement.execute("INSERT INTO sessions VALUES('keep me')");
        }
        assertThrows(IllegalStateException.class, () -> new SqliteDatabase(paths));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = c.createStatement()) {
            try (var rows = statement.executeQuery("SELECT keep FROM sessions")) { assertTrue(rows.next()); assertEquals("keep me", rows.getString(1)); }
            try (var rows = statement.executeQuery("PRAGMA user_version")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            try (var rows = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='projects'")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        }
    }

    @Test void persistsOrderedMessagesAndCascadesDeletesIncludingPartsAndTools() throws Exception {
        try (var f = new SessionFixture(root)) {
            var session = f.sessions.create("qwen3:8b", "Ollama");
            var one = f.conversation.add(session.id(), "user", "first", MessageStatus.COMPLETE);
            var two = f.conversation.add(session.id(), "assistant", "second", MessageStatus.COMPLETE);
            f.conversation.part(two.id(), PartType.REASONING, Map.of("text", "private"));
            f.repositories.saveExecution(new ToolExecution(UUID.randomUUID().toString(), session.id(), two.id(), "call", "read", "{}", ToolStatus.SUCCESS, "POLICY_ALLOWED", "result", java.time.Instant.now().toString(), null, null));
            assertEquals(List.of(0, 1), f.conversation.messages(session.id()).stream().map(Message::position).toList());
            assertEquals("first", f.conversation.history(session.id()).getFirst().content());
            assertEquals(2, f.conversation.parts(two.id()).size());
            f.sessions.delete(session.id());
            assertTrue(f.repositories.findSession(session.id()).isEmpty());
            assertTrue(f.conversation.parts(one.id()).isEmpty());
            assertTrue(f.conversation.messages(session.id()).isEmpty());
            assertTrue(f.repositories.listExecutions(session.id()).isEmpty());
            assertThrows(IllegalStateException.class, () -> f.conversation.add("missing", "user", "orphan", MessageStatus.COMPLETE));
        }
    }

    @Test void rollsBackMultipleEntitiesWhenAnOperationFails() throws Exception {
        try (var f = new SessionFixture(root)) {
            assertThrows(IllegalStateException.class, () -> f.database.transaction(() -> {
                var s = f.sessions.create("qwen3:8b", "Ollama");
                var m = f.conversation.add(s.id(), "user", "rollback", MessageStatus.COMPLETE);
                f.repositories.addPart(m.id(), PartType.TEXT, "invalid json");
                return null;
            }));
            assertTrue(f.sessions.list().isEmpty());
        }
    }

    @Test void serializesConcurrentWritersAcrossConnections() throws Exception {
        try (var f = new SessionFixture(root); var g = new SessionFixture(root); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var s = f.sessions.create("qwen3:8b", "Ollama");
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) {
                int index = i;
                tasks.add(executor.submit(() -> (index % 2 == 0 ? f : g).conversation.add(s.id(), "user", "message " + index, MessageStatus.COMPLETE)));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(40, f.conversation.messages(s.id()).size());
            assertEquals(java.util.stream.IntStream.range(0, 40).boxed().toList(), f.conversation.messages(s.id()).stream().map(Message::position).toList());
        }
    }

    @Test void forksFullHistoryWithoutChangingParentAndPreservesChildrenOnParentDelete() throws Exception {
        try (var f = new SessionFixture(root)) {
            var s = f.sessions.create("qwen3:8b", "Ollama");
            f.conversation.add(s.id(), "user", "original", MessageStatus.COMPLETE);
            var fork = f.sessions.fork(s.id());
            assertEquals(s.id(), fork.parentSessionId());
            assertNotEquals(s.id(), fork.id());
            f.conversation.add(fork.id(), "assistant", "only fork", MessageStatus.COMPLETE);
            assertEquals(1, f.conversation.messages(s.id()).size());
            assertEquals(2, f.conversation.messages(fork.id()).size());
            assertEquals("original", f.conversation.history(fork.id()).getFirst().content());
            var exported = f.mapper.instance().fromString(Map.class, f.sessions.export(fork.id()));
            assertEquals(2, ((List<?>) exported.get("messages")).size());
            f.sessions.delete(s.id());
            assertNull(f.sessions.load(fork.id()).parentSessionId());
        }
    }

    @Test void recoversAbandonedGenerationButDoesNotInterruptAnotherLiveInstance() throws Exception {
        try (var f = new SessionFixture(root); var g = new SessionFixture(root)) {
            var s = f.sessions.create("qwen3:8b", "Ollama");
            var m = f.conversation.add(s.id(), "assistant", "saved fragment", MessageStatus.GENERATING);
            try (var lease = f.sessions.acquire(s.id())) {
                assertThrows(IllegalStateException.class, () -> g.sessions.delete(s.id()));
                assertThrows(IllegalStateException.class, () -> g.sessions.acquire(s.id()));
                g.sessions.list();
                assertEquals(MessageStatus.GENERATING, f.conversation.messages(s.id()).getFirst().status());
            }
            assertEquals(SessionStatus.INTERRUPTED, g.sessions.recover(s.id()).status());
            assertEquals(MessageStatus.INTERRUPTED, g.conversation.messages(s.id()).getFirst().status());
            assertTrue(g.conversation.history(s.id()).getFirst().content().contains("saved fragment"));
        }
    }

    @Test void isolatesProjectsAndRejectsCrossProjectAccess() throws Exception {
        try (var f = new SessionFixture(root.resolve("a")); var g = new SessionFixture(root.resolve("b"))) {
            var s = f.sessions.create("qwen3:8b", "Ollama");
            // Same global database, different current project.
            var otherProjects = new ProjectService(g.config, f.repositories);
            var other = new SessionService(otherProjects, f.repositories, f.repositories, f.repositories, f.repositories, f.database, new SessionLocks(f.paths), f.mapper.instance());
            assertTrue(other.list().isEmpty());
            assertTrue(assertThrows(IllegalArgumentException.class, () -> other.load(s.id())).getMessage().contains("different project"));
        }
    }

    @Test void resumesTheMostRecentlyOpenedSessionAndPersistsRenaming() throws Exception {
        try (var f = new SessionFixture(root)) {
            var first = f.sessions.create("qwen3:8b", "Ollama");
            var second = f.sessions.create("qwen3:8b", "Ollama");
            assertEquals(second.id(), f.sessions.latest().orElseThrow().id());
            f.sessions.rename(first.id(), "A meaningful title");
            f.sessions.open(first.id());
            assertEquals(first.id(), f.sessions.latest().orElseThrow().id());
            assertEquals("A meaningful title", f.sessions.load(first.id()).title());
        }
    }

    @Test void resolvesDataLocationsAndProtectsAgainstRepositoryStorageAndTraversal() throws Exception {
        var home = root.resolve("home");
        assertEquals(home.resolve(".local/share/syleks/syleks.db"), DataPaths.configured(home, Map.of()).database());
        assertEquals(root.resolve("xdg/syleks/syleks.db"), DataPaths.configured(home, Map.of("XDG_DATA_HOME", root.resolve("xdg").toString())).database());
        var override = DataPaths.configured(home, Map.of("XDG_DATA_HOME", root.resolve("xdg").toString(), "SYLEKS_DATA_DIR", root.resolve("override").toString()));
        assertEquals(root.resolve("override/syleks.db"), override.database());
        Files.createDirectories(root.resolve("repo/.git"));
        Files.writeString(root.resolve("repo/.git/HEAD"), "ref: refs/heads/main");
        assertThrows(IllegalStateException.class, () -> DataPaths.configured(home, Map.of("SYLEKS_DATA_DIR", root.resolve("repo/data").toString())));
        assertThrows(IllegalArgumentException.class, () -> new SessionLocks(override).acquire("../../escape"));
        if (Files.getFileStore(override.directory()).supportsFileAttributeView("posix")) {
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(override.directory())));
        }
    }
}
