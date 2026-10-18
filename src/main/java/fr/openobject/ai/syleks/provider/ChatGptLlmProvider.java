package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.auth.AuthenticationService;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.net.URI;

@DefaultScoped
public class ChatGptLlmProvider extends ResponsesLlmProvider {
    public ChatGptLlmProvider(AuthenticationService auth, JsonMapper json, AppConfig config) {
        this(auth, json, config, URI.create("https://api.openai.com/v1/"));
    }

    ChatGptLlmProvider(AuthenticationService auth, JsonMapper json, AppConfig config, URI base) {
        super(auth, json, config, base);
    }

    public String id() {
        return "chatgpt";
    }

    public String displayName() {
        return "ChatGPT";
    }
}
