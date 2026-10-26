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

import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.auth.AuthenticationService;
import fr.openobject.ai.syleks.auth.Credentials;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Chat completions for a built-in provider whose key lives in the credential store. */
abstract class ApiKeyChatProvider implements LlmProvider {
    private final AuthenticationService auth;
    private final JsonMapper json;
    private final AppConfig config;
    private final OllamaClient client;
    private final URI chat;
    private volatile HttpClient http;

    ApiKeyChatProvider(
            AuthenticationService auth,
            JsonMapper json,
            AppConfig config,
            OllamaClient client,
            URI chat) {
        this.auth = auth;
        this.json = json;
        this.config = config;
        this.client = client;
        this.chat = chat;
    }

    public boolean requiresAccount() {
        return true;
    }

    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(false, true, true);
    }

    protected final JsonMapper json() {
        return json;
    }

    protected final AuthenticationService auth() {
        return auth;
    }

    public void chat(ChatRequest request, ChatEventListener listener) throws Exception {
        validateModel(request.model());
        stream(chat, request, listener);
    }

    protected final void stream(URI endpoint, ChatRequest request, ChatEventListener listener)
            throws Exception {
        config.transport().requireAllowed(endpoint);
        var credentials = credentials(request.accountId());
        var connection =
                new AppConfig.ModelConnection(
                        endpoint,
                        request.model(),
                        displayName(),
                        request.model(),
                        credentials.accessToken(),
                        null,
                        null,
                        true);
        var original = request.observer();
        try {
            client.streamPrepared(
                    connection,
                    id() + "/" + request.model(),
                    request.agent(),
                    request.messages(),
                    delta ->
                            listener.onEvent(new ChatEvent(ChatEvent.Type.TEXT_DELTA, delta, null)),
                    request.tools(),
                    ProviderEvents.observer(original, listener));
            listener.onEvent(new ChatEvent(ChatEvent.Type.COMPLETED, "", null));
        } catch (Exception e) {
            listener.onEvent(new ChatEvent(ChatEvent.Type.ERROR, safe(e.getMessage()), null));
            throw e;
        }
    }

    protected final Credentials credentials(String accountId) throws Exception {
        var stored = auth.credentials(accountId);
        if (!stored.account().providerId().equals(id()))
            throw new IOException(
                    "Account belongs to another provider; select an account explicitly");
        if (stored.accessToken() == null || !stored.accessToken().matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IOException("Invalid stored access token; reconnect the selected account");
        return stored;
    }

    protected final String fetch(URI uri, String token) throws Exception {
        config.transport().requireAllowed(uri);
        var response =
                http().send(
                                HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(20))
                                        .header("Authorization", "Bearer " + token)
                                        .header("Accept", "application/json")
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024)
                throw new IOException("Provider model catalog exceeds limit");
            var value = new String(bytes, StandardCharsets.UTF_8);
            if (response.statusCode() != 200)
                throw new IOException(
                        displayName()
                                + " model catalog HTTP "
                                + response.statusCode()
                                + " : "
                                + redact(value, token));
            return value;
        }
    }

    protected final Map<?, ?> object(String value) throws IOException {
        try {
            return json.fromString(Map.class, value);
        } catch (RuntimeException e) {
            throw new IOException("Invalid provider model catalog");
        }
    }

    static boolean modelId(String id) {
        return id != null && id.matches("[A-Za-z0-9._:/-]{1,200}");
    }

    private static String redact(String body, String token) {
        return fr.openobject.ai.syleks.auth.SecretRedaction.diagnostic(body, token);
    }

    private static String safe(String message) {
        return message == null ? "Provider request failed" : message;
    }

    private synchronized HttpClient http() {
        if (http == null) {
            http =
                    config.configureProxy(
                                    id(),
                                    HttpClient.newBuilder()
                                            .connectTimeout(Duration.ofSeconds(10))
                                            .followRedirects(HttpClient.Redirect.NEVER))
                            .build();
        }
        return http;
    }
}
