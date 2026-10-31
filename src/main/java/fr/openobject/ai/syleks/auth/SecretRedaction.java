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

import fr.openobject.ai.syleks.json.JsonIO;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded diagnostic-only redaction, never used for requests or user-authored exports. */
public final class SecretRedaction {
    private static final JsonIO JSON = new JsonIO();

    private SecretRedaction() {}

    public static Object structured(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (key, item) -> {
                        String name = String.valueOf(key);
                        result.put(name, sensitive(name) ? "[redacted]" : structured(item));
                    });
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream().map(SecretRedaction::structured).toList();
        return value;
    }

    private static boolean sensitive(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .matches(
                        ".*(authorization|cookie|password|secret|token|apikey|headers|environment).*");
    }

    public static String diagnostic(String value, String... secrets) {
        if (value == null) return "Transport operation failed";
        String result = value;
        try {
            result = JSON.write(structured(JSON.read(value, Object.class)));
        } catch (Exception ignored) {
        }
        // Replace before bounding: truncation must not expose the beginning of a credential.
        for (String secret : secrets)
            if (secret != null && !secret.isEmpty()) {
                result =
                        result.replace(secret, "[redacted]")
                                .replace(
                                        URLEncoder.encode(secret, StandardCharsets.UTF_8),
                                        "[redacted]");
                try {
                    String quoted = JSON.write(secret);
                    result = result.replace(quoted.substring(1, quoted.length() - 1), "[redacted]");
                } catch (Exception ignored) {
                }
            }
        result = result.replaceAll("(?i)https?://[^\\s\\x22<>]+", "[url omitted]");
        return fr.openobject.ai.syleks.provider.ProviderException.safe(result);
    }
}
