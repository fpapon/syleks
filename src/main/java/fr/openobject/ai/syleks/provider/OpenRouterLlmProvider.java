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
import fr.openobject.ai.syleks.auth.AuthenticationService;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** OpenRouter chat completions. Model ids keep their author/slug form. */
@DefaultScoped
public class OpenRouterLlmProvider extends ApiKeyChatProvider {
    private final URI models;

    public OpenRouterLlmProvider(
            AuthenticationService auth, JsonMapper json, AppConfig config, OllamaClient client) {
        this(
                auth,
                json,
                config,
                client,
                URI.create("https://openrouter.ai/api/v1/models"),
                URI.create("https://openrouter.ai/api/v1/chat/completions"));
    }

    OpenRouterLlmProvider(
            AuthenticationService auth,
            JsonMapper json,
            AppConfig config,
            OllamaClient client,
            URI models,
            URI chat) {
        super(auth, json, config, client, chat);
        this.models = models;
    }

    public String id() {
        return "openrouter";
    }

    public String displayName() {
        return "OpenRouter";
    }

    public List<LlmModel> listModels(String accountId) throws Exception {
        var body = object(fetch(models, credentials(accountId).accessToken()));
        if (!(body.get("data") instanceof List<?> list))
            throw new IOException("Invalid OpenRouter model catalog");
        var found = new ArrayList<LlmModel>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> model) || !(model.get("id") instanceof String id))
                continue;
            if (!modelId(id)) continue;
            String label = model.get("name") instanceof String name && !name.isBlank() ? name : id;
            found.add(new LlmModel(id(), id, label, capabilities()));
        }
        return List.copyOf(found);
    }
}
