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
                        f.config,
                        legacy);
        registry.initialize();
        service = new ProviderService(registry, f.store, f.auth, f.config, f.json);
        service.initialize();
    }
}
