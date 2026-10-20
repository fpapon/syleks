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
package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.auth.AuthenticationService;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.net.URI;

@DefaultScoped
public class ChatGptLlmProvider extends ResponsesLlmProvider {
    @io.yupiik.fusion.framework.build.api.scanning.Injection
    fr.openobject.ai.syleks.ai.AgentRuntime engine;

    @io.yupiik.fusion.framework.build.api.scanning.Injection
    fr.openobject.ai.syleks.ai.ToolRegistry registry;

    @Override
    protected fr.openobject.ai.syleks.ai.ToolRegistry tools() {
        return registry == null ? super.tools() : registry;
    }

    @Override
    protected fr.openobject.ai.syleks.ai.AgentRuntime runtime() {
        return engine == null ? super.runtime() : engine;
    }

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
