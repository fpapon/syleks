/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
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
