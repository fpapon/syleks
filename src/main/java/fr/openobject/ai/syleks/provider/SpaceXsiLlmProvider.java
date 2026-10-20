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

/** SpaceXSI Grok models. The key is XAI_API_KEY and the host is api.x.ai. */
@DefaultScoped
public class SpaceXsiLlmProvider extends ApiKeyChatProvider {
    private final URI models;

    public SpaceXsiLlmProvider(
            AuthenticationService auth, JsonMapper json, AppConfig config, OllamaClient client) {
        this(
                auth,
                json,
                config,
                client,
                URI.create("https://api.x.ai/v1/language-models"),
                URI.create("https://api.x.ai/v1/chat/completions"));
    }

    SpaceXsiLlmProvider(
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
        return "spacexsi";
    }

    public String displayName() {
        return "SpaceXSI";
    }

    public List<LlmModel> listModels(String accountId) throws Exception {
        var body = object(fetch(models, credentials(accountId).accessToken()));
        if (!(body.get("models") instanceof List<?> list))
            throw new IOException("Invalid SpaceXSI model catalog");
        var found = new ArrayList<LlmModel>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> model) || !(model.get("id") instanceof String id))
                continue;
            if (!modelId(id)) continue;
            if (!(model.get("output_modalities") instanceof List<?> modalities)
                    || !modalities.contains("text")) continue;
            found.add(new LlmModel(id(), id, id, capabilities()));
        }
        return List.copyOf(found);
    }
}
