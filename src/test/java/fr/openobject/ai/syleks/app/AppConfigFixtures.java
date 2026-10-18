package fr.openobject.ai.syleks.app;

import io.yupiik.fusion.json.JsonMapper;

import java.nio.file.Path;
import java.util.Map;

/** Creates test configurations without reading the user's home, environment or project agents. */
public final class AppConfigFixtures {
    private AppConfigFixtures() {
    }

    public static AppConfig defaults(JsonMapper json, Path directory) {
        return new AppConfig(json, directory.resolve("home"), Map.of(), directory.resolve("project"));
    }
}
