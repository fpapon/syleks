package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.Map;

/** Proves Fusion discovery and constructor injection without modifying the production registry. */
@DefaultScoped
public class FixtureCustomTool implements CustomTool {
    private final AppConfig config;

    public FixtureCustomTool(AppConfig config) {
        this.config = config;
    }

    public ToolDefinition definition() {
        return new ToolDefinition(
                "custom_fixture",
                "Fixture constructor injection",
                Map.of("type", "object", "additionalProperties", false),
                true);
    }

    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        if (!context.workspace().equals(config.workspace()))
            throw new AssertionError("Wrong injected config");
        return ToolResult.success("FUSION_EXTENSION_OK");
    }
}
