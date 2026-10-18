package fr.openobject.ai.syleks.auth;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.net.URI;

/** Published OpenAI identity endpoints. Production endpoints cannot be overridden by project files. */
@DefaultScoped
public class OpenAiOAuthEndpoints {
    public String issuer() {
        return "https://auth.openai.com";
    }

    public URI jwks() {
        return URI.create(issuer() + "/.well-known/jwks.json");
    }
}
