package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.auth.AuthenticationService;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.net.URI;

@DefaultScoped
public class OpenAiApiLlmProvider extends ResponsesLlmProvider {
    @io.yupiik.fusion.framework.build.api.scanning.Injection
    fr.openobject.ai.syleks.ai.AgentRuntime engine;

    @Override
    protected fr.openobject.ai.syleks.ai.AgentRuntime runtime() {
        return engine == null ? super.runtime() : engine;
    }

    public OpenAiApiLlmProvider(AuthenticationService auth, JsonMapper json, AppConfig config) {
        this(auth, json, config, URI.create("https://api.openai.com/v1/"));
    }

    OpenAiApiLlmProvider(AuthenticationService auth, JsonMapper json, AppConfig config, URI base) {
        super(auth, json, config, base);
    }

    public String id() {
        return "openai-api";
    }

    public String displayName() {
        return "OpenAI API";
    }
}
