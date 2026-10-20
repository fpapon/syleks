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
package fr.openobject.ai.syleks.app;

import io.yupiik.fusion.json.JsonMapper;
import java.nio.file.Path;
import java.util.Map;

/** Creates test configurations without reading the user's home, environment or project agents. */
public final class AppConfigFixtures {
    private AppConfigFixtures() {}

    public static AppConfig defaults(JsonMapper json, Path directory) {
        return atWorkspace(json, directory, directory.resolve("project"));
    }

    public static AppConfig atWorkspace(JsonMapper json, Path directory, Path workspace) {
        return atWorkspace(json, directory, workspace, Map.of());
    }

    public static AppConfig atWorkspace(
            JsonMapper json, Path directory, Path workspace, Map<String, String> environment) {
        return new AppConfig(json, directory.resolve("home"), environment, workspace);
    }
}
