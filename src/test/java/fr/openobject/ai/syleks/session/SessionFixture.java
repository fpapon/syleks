package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.app.*;
import fr.openobject.ai.syleks.session.storage.*;
import io.yupiik.fusion.framework.api.*;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.file.*;
import java.util.Map;

public final class SessionFixture implements AutoCloseable {
    public final RuntimeContainer container = ConfiguringContainer.of().start();
    public final Instance<JsonMapper> mapper = container.lookup(JsonMapper.class);
    public final AppConfig config;
    public final DataPaths paths;
    public final SqliteDatabase database;
    public final SqliteRepositories repositories;
    public final ProjectService projects;
    public final SessionService sessions;
    public final ConversationService conversation;
    public final ContextService context;
    public final AgentService agent;

    public SessionFixture(Path root) throws Exception {
        this(root, root.resolve("project"));
    }

    public SessionFixture(Path root, Path workspace) throws Exception {
        this(root, workspace, Map.of());
    }

    public SessionFixture(Path root, Path workspace, Map<String, String> environment)
            throws Exception {
        Files.createDirectories(workspace);
        config = AppConfigFixtures.atWorkspace(mapper.instance(), root, workspace, environment);
        paths =
                DataPaths.configured(
                        root.resolve("home"),
                        Map.of("SYLEKS_DATA_DIR", root.resolve("data").toString()));
        database = new SqliteDatabase(paths);
        repositories = new SqliteRepositories(database);
        projects = new ProjectService(config, repositories);
        sessions =
                new SessionService(
                        projects,
                        repositories,
                        repositories,
                        repositories,
                        repositories,
                        database,
                        new SessionLocks(paths),
                        mapper.instance());
        conversation =
                new ConversationService(repositories, repositories, database, mapper.instance());
        context = new ContextService(conversation, config, projects, mapper.instance());
        agent =
                new AgentService(
                        sessions, conversation, context, config, repositories, mapper.instance());
        agent.tokenUsage(
                new fr.openobject.ai.syleks.usage.TokenUsageService(
                        new fr.openobject.ai.syleks.usage.TokenUsageRepository(database)));
    }

    @Override
    public void close() {
        agent.close();
        mapper.close();
        container.close();
    }
}
