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
package fr.openobject.ai.syleks.auth;

import fr.openobject.ai.syleks.provider.ProviderException;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

@DefaultScoped
public class OAuthHttp {
    private final JsonMapper json;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public OAuthHttp(JsonMapper json) {
        this.json = json;
    }

    public static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public Map<?, ?> get(URI uri) throws Exception {
        return send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                new String[0]);
    }

    public Map<?, ?> post(URI uri, Map<String, String> values) throws Exception {
        return send(
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(values)))
                        .build(),
                values.values().toArray(String[]::new));
    }

    private Map<?, ?> send(HttpRequest request, String... secrets) throws Exception {
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        Map<?, ?> result;
        try (var body = response.body()) {
            byte[] bytes = body.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024)
                throw new java.io.IOException("OAuth response too large");
            String text = new String(bytes, StandardCharsets.UTF_8);
            try {
                result = text.isBlank() ? Map.of() : json.fromString(Map.class, text);
            } catch (RuntimeException e) {
                throw new java.io.IOException(
                        "Invalid OAuth server response (HTTP " + response.statusCode() + ")");
            }
        }
        if (response.statusCode() != 200) {
            // OAuth error descriptions may echo authorization codes; only retain machine codes.
            Object error = result.get("error");
            var sanitized =
                    error instanceof String s && s.matches("[a-z_]{1,100}")
                            ? Map.of("error", s)
                            : Map.of();
            throw ProviderException.from(
                    response.statusCode(),
                    sanitized,
                    response.headers().firstValue("x-request-id").orElse(null),
                    secrets);
        }
        return result;
    }
}
