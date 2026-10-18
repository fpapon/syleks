package fr.openobject.ai.syleks.auth;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.net.URI;
import java.nio.file.Path;

public final class AuthProcess {
    public static void main(String[] args) throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var store = new CredentialStore(mapper.instance(), Path.of(args[0]));
            new TokenRefreshService(store, new OAuthHttp(mapper.instance()), URI.create(args[1]))
                    .usable(args[2]);
        }
    }
}
