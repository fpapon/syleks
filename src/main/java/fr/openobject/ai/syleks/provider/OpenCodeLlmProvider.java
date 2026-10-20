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
package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.ai.AgentRuntime;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.ai.ToolRegistry;
import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.auth.AuthenticationService;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenCode Console. Responses and chat-completions models are selectable. Anthropic, Google and
 * System One models stay visible and are refused before any request.
 */
@DefaultScoped
public class OpenCodeLlmProvider extends ApiKeyChatProvider {
    @io.yupiik.fusion.framework.build.api.scanning.Injection AgentRuntime engine;

    @io.yupiik.fusion.framework.build.api.scanning.Injection ToolRegistry registry;

    private final URI catalog;
    private final URI chat;
    private final ResponsesTransport responses;

    public OpenCodeLlmProvider(
            AuthenticationService auth, JsonMapper json, AppConfig config, OllamaClient client) {
        this(
                auth,
                json,
                config,
                client,
                URI.create("https://opencode.ai/inference/v1/models"),
                URI.create("https://opencode.ai/inference/openai/v1/"),
                URI.create("https://opencode.ai/inference/openai/v1/chat/completions"));
    }

    OpenCodeLlmProvider(
            AuthenticationService auth,
            JsonMapper json,
            AppConfig config,
            OllamaClient client,
            URI catalog,
            URI responsesBase,
            URI chat) {
        super(auth, json, config, client, chat);
        this.catalog = catalog;
        this.chat = chat;
        this.responses =
                new ResponsesTransport(auth, json, config, responsesBase, "opencode", "OpenCode");
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    void initialize() {
        responses.bind(engine, registry);
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Destroy
    public void close() {
        responses.close();
    }

    public String id() {
        return "opencode";
    }

    public String displayName() {
        return "OpenCode";
    }

    public void validateModel(String modelId) throws IOException {
        var route = OpenCodeTransports.classify(modelId);
        if (!route.selectable()) throw new IOException(route.reason());
    }

    public List<LlmModel> listModels(String accountId) throws Exception {
        var body = object(fetch(catalog, credentials(accountId).accessToken()));
        if (!(body.get("data") instanceof List<?> list))
            throw new IOException("Invalid OpenCode model catalog");
        var found = new ArrayList<LlmModel>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> model) || !(model.get("id") instanceof String id))
                continue;
            if (!modelId(id)) continue;
            var route = OpenCodeTransports.classify(id);
            String label = route.selectable() ? id : id + " · " + route.reason();
            found.add(new LlmModel(id(), id, label, null));
        }
        return List.copyOf(found);
    }

    @Override
    public void chat(ChatRequest request, ChatEventListener listener) throws Exception {
        validateModel(request.model());
        switch (OpenCodeTransports.classify(request.model()).kind()) {
            case RESPONSES -> responses.chat(request, listener);
            case CHAT -> stream(chat, request, listener);
            case UNSUPPORTED ->
                    throw new IOException(OpenCodeTransports.classify(request.model()).reason());
        }
    }
}
