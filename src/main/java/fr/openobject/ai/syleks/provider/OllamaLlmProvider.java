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

import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.usage.*;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@DefaultScoped
public class OllamaLlmProvider implements LlmProvider {
    private final OllamaClient client;
    private final AppConfig config;
    private final JsonMapper json;

    public OllamaLlmProvider(OllamaClient client, AppConfig config, JsonMapper json) {
        this.client = client;
        this.config = config;
        this.json = json;
    }

    public String id() {
        return "ollama";
    }

    public String displayName() {
        return "Ollama";
    }

    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                java.util.Set.of("localhost", "127.0.0.1", "[::1]")
                        .contains(config.endpoint().getHost()),
                true,
                true);
    }

    public List<LlmModel> listModels(String accountId) throws Exception {
        var endpoint = config.resolveModel("ollama/placeholder").endpoint();
        URI tags = URI.create(endpoint.toString().replaceAll("/api/chat$", "/api/tags"));
        config.transport().requireAllowed(tags);
        var response =
                config.configureProxy(
                                id(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)))
                        .build()
                        .send(
                                HttpRequest.newBuilder(tags)
                                        .timeout(Duration.ofSeconds(8))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new java.io.IOException("Ollama model discovery HTTP " + response.statusCode());
        Map<?, ?> body = json.fromString(Map.class, response.body());
        var models = new ArrayList<LlmModel>();
        if (body.get("models") instanceof List<?> list)
            for (var item : list) {
                if (item instanceof Map<?, ?> model && model.get("name") instanceof String name)
                    models.add(new LlmModel(id(), name, name, capabilities()));
            }
        return List.copyOf(models);
    }

    public void chat(ChatRequest request, ChatEventListener listener) throws Exception {
        var target =
                request.model().startsWith("ollama/")
                        ? request.model()
                        : "ollama/" + request.model();
        stream(client, target, request, listener);
    }

    static void stream(
            OllamaClient client, String model, ChatRequest request, ChatEventListener listener)
            throws Exception {
        var original = request.observer();
        try {
            client.streamPrepared(
                    model,
                    request.agent(),
                    request.messages(),
                    delta ->
                            listener.onEvent(new ChatEvent(ChatEvent.Type.TEXT_DELTA, delta, null)),
                    request.tools(),
                    ProviderEvents.observer(original, listener));
            listener.onEvent(new ChatEvent(ChatEvent.Type.COMPLETED, "", null));
        } catch (Exception e) {
            listener.onEvent(new ChatEvent(ChatEvent.Type.ERROR, e.getMessage(), null));
            throw e;
        }
    }
}
