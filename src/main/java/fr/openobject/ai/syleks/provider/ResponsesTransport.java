package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.auth.AuthenticationService;
import io.yupiik.fusion.json.JsonMapper;
import java.net.URI;

/** Responses SSE for an account-backed provider that is not ChatGPT or the OpenAI API. */
final class ResponsesTransport extends ResponsesLlmProvider {
    private final String providerId;
    private final String name;

    ResponsesTransport(
            AuthenticationService auth,
            JsonMapper json,
            AppConfig config,
            URI base,
            String providerId,
            String name) {
        super(auth, json, config, base);
        this.providerId = providerId;
        this.name = name;
    }

    public String id() {
        return providerId;
    }

    public String displayName() {
        return name;
    }
}
