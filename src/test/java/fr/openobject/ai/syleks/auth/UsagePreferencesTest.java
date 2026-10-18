package fr.openobject.ai.syleks.auth;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.usage.UsagePreferences;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsagePreferencesTest {
    @TempDir Path root;

    @Test
    void persistsVisibilityPreservesProviderSelectionAndValidatesThresholds() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var store = new CredentialStore(mapper.instance(), root);
            var file = root.resolve("config.json");
            store.write(
                    file,
                    Map.of(
                            "provider",
                            "ollama",
                            "model",
                            "qwen",
                            "context_warning_percent",
                            60,
                            "context_critical_percent",
                            85));
            var preferences = new UsagePreferences(store);
            preferences.load();
            assertEquals(60, preferences.warning());
            assertEquals(85, preferences.critical());
            preferences.select("");
            assertEquals(UsagePreferences.Visibility.HIDE, preferences.visibility());
            preferences.select("");
            assertEquals(UsagePreferences.Visibility.SHOW, preferences.visibility());
            var restored = new UsagePreferences(store);
            restored.load();
            assertEquals(UsagePreferences.Visibility.SHOW, restored.visibility());
            restored.select("auto");
            assertEquals("qwen", store.read(file).get("model"));
            assertEquals("ollama", store.read(file).get("provider"));
            store.write(
                    file, Map.of("context_warning_percent", 95, "context_critical_percent", 70));
            assertThrows(IllegalArgumentException.class, restored::load);
        }
    }
}
