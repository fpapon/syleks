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
import fr.openobject.ai.syleks.auth.AuthFixture;

public final class ProviderFixture {
    public final ProviderRegistry registry;
    public final ProviderService service;
    public final ChatGptLlmProvider chatgpt;

    public ProviderFixture(AuthFixture f) {
        var legacy = new OllamaClient(f.config, f.json);
        chatgpt = new ChatGptLlmProvider(f.auth, f.json, f.config, f.base.resolve("v1/"));
        registry =
                new ProviderRegistry(
                        new OllamaLlmProvider(legacy, f.config, f.json),
                        chatgpt,
                        new OpenAiApiLlmProvider(f.auth, f.json, f.config),
                        new SpaceXsiLlmProvider(
                                f.auth,
                                f.json,
                                f.config,
                                legacy,
                                f.base.resolve("xai/language-models"),
                                f.base.resolve("xai/chat/completions")),
                        new OpenRouterLlmProvider(
                                f.auth,
                                f.json,
                                f.config,
                                legacy,
                                f.base.resolve("openrouter/models"),
                                f.base.resolve("openrouter/chat/completions")),
                        new OpenCodeLlmProvider(
                                f.auth,
                                f.json,
                                f.config,
                                legacy,
                                f.base.resolve("opencode/models"),
                                f.base.resolve("opencode/openai/v1/"),
                                f.base.resolve("opencode/openai/v1/chat/completions")),
                        new GoogleLlmProvider(
                                f.auth,
                                f.json,
                                f.config,
                                legacy,
                                f.base.resolve("google/models"),
                                f.base.resolve("google/chat/completions")),
                        f.config,
                        legacy);
        registry.initialize();
        service = new ProviderService(registry, f.store, f.auth, f.config, f.json);
        service.initialize();
    }
}
