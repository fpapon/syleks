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
