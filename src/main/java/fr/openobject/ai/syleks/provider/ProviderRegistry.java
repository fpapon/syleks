package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.app.AppConfig;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.*;

@ApplicationScoped
public class ProviderRegistry {
    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
    private final OllamaLlmProvider ollama;
    private final ChatGptLlmProvider chatgpt;
    private final OpenAiApiLlmProvider api;
    private final AppConfig config;
    private final OllamaClient legacy;

    public ProviderRegistry(
            OllamaLlmProvider ollama,
            ChatGptLlmProvider chatgpt,
            OpenAiApiLlmProvider api,
            AppConfig config,
            OllamaClient legacy) {
        this.ollama = ollama;
        this.chatgpt = chatgpt;
        this.api = api;
        this.config = config;
        this.legacy = legacy;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    void initialize() {
        register(chatgpt);
        register(ollama);
        register(api);
        for (String id : config.configuredProviders()) {
            if (providers.containsKey(id))
                throw new IllegalArgumentException(
                        "Reserved provider ID in agent-config.json: " + id);
            register(
                    new LlmProvider() {
                        public String id() {
                            return id;
                        }

                        public String displayName() {
                            return config.configuredProviderName(id);
                        }

                        public ProviderCapabilities capabilities() {
                            return new ProviderCapabilities(
                                    config.configuredModels(id).stream()
                                            .allMatch(
                                                    c ->
                                                            Set.of(
                                                                            "localhost",
                                                                            "127.0.0.1",
                                                                            "[::1]")
                                                                    .contains(
                                                                            c.endpoint()
                                                                                    .getHost())),
                                    true,
                                    true);
                        }

                        public List<LlmModel> listModels(String account) {
                            return config.configuredModels(id).stream()
                                    .map(c -> new LlmModel(id, c.model(), c.displayName(), null))
                                    .toList();
                        }

                        public void chat(ChatRequest request, ChatEventListener listener)
                                throws Exception {
                            OllamaLlmProvider.stream(
                                    legacy, id + "/" + request.model(), request, listener);
                        }
                    });
        }
    }

    public synchronized void register(LlmProvider provider) {
        if (providers.putIfAbsent(provider.id(), provider) != null)
            throw new IllegalArgumentException("Duplicate provider: " + provider.id());
    }

    public synchronized List<LlmProvider> providers() {
        return List.copyOf(providers.values());
    }

    public synchronized LlmProvider get(String id) {
        var provider = providers.get(id);
        if (provider == null) throw new IllegalArgumentException("Provider unavailable: " + id);
        return provider;
    }

    public String providerId(String selection) {
        int slash = selection.indexOf('/');
        if (slash > 0 && providers.containsKey(selection.substring(0, slash)))
            return selection.substring(0, slash);
        return "ollama";
    }

    public String modelId(String selection) {
        String provider = providerId(selection);
        return selection.startsWith(provider + "/")
                ? selection.substring(provider.length() + 1)
                : selection;
    }
}
