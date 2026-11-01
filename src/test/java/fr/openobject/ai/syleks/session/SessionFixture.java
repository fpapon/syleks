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
        this(root, workspace, Map.of("LANG", "en_US.UTF-8"));
    }

    public SessionFixture(
            Path root, Path workspace, java.net.URI ollamaEndpoint, Map<String, String> environment)
            throws Exception {
        this(configureOllama(root, ollamaEndpoint), workspace, environment);
    }

    private static Path configureOllama(Path root, java.net.URI endpoint) throws Exception {
        var file = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        try (var container = ConfiguringContainer.of().start();
                var json = container.lookup(JsonMapper.class)) {
            var settings = new java.util.LinkedHashMap<String, Object>();
            if (Files.exists(file)) {
                Map<?, ?> existing = json.instance().fromString(Map.class, Files.readString(file));
                existing.forEach((key, value) -> settings.put(key.toString(), value));
            }
            var providers = new java.util.LinkedHashMap<String, Object>();
            ((Map<?, ?>) settings.getOrDefault("provider", Map.of()))
                    .forEach((key, value) -> providers.put(key.toString(), value));
            providers.put("ollama", Map.of("options", Map.of("baseURL", endpoint.toString())));
            settings.put("provider", providers);
            Files.writeString(file, json.instance().toString(settings));
        }
        return root;
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
