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
package fr.openobject.ai.syleks.i18n;

import fr.openobject.ai.syleks.app.AppConfig;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Interface catalogs. Lookup is the active catalog, then {@code en_US}, then {@code fr_FR}, then
 * the key. The JVM locale is not a bundle parent.
 */
@DefaultScoped
public final class Messages {
    private static final Map<String, String> EN = load("en_US");
    private static final Map<String, String> FR = load("fr_FR");
    private static volatile Messages current = new Messages(EN, EN, FR);

    private final Map<String, String> active;
    private final Map<String, String> english;
    private final Map<String, String> french;

    public Messages(AppConfig config) {
        this(activeFor(config.interfaceLanguage().catalog()), EN, FR);
        install(this);
    }

    private Messages(
            Map<String, String> active, Map<String, String> english, Map<String, String> french) {
        this.active = active;
        this.english = english;
        this.french = french;
    }

    public static Messages current() {
        return current;
    }

    public static void install(Messages messages) {
        current = messages == null ? new Messages(EN, EN, FR) : messages;
    }

    public static Messages forCatalog(String catalog) {
        return new Messages(activeFor(catalog), EN, FR);
    }

    public static String text(String key, Object... args) {
        return current.get(key, args);
    }

    public static Set<String> englishKeys() {
        return EN.keySet();
    }

    public static Set<String> frenchKeys() {
        return FR.keySet();
    }

    public String get(String key, Object... args) {
        String pattern = lookup(key);
        if (args == null || args.length == 0) return pattern;
        return new MessageFormat(pattern, Locale.ROOT).format(args);
    }

    private String lookup(String key) {
        String value = active.get(key);
        if (value != null) return value;
        value = english.get(key);
        if (value != null) return value;
        value = french.get(key);
        return value == null ? key : value;
    }

    static Messages of(
            Map<String, String> active, Map<String, String> english, Map<String, String> french) {
        return new Messages(active, english, french);
    }

    private static Map<String, String> activeFor(String catalog) {
        return "fr_FR".equals(catalog) ? FR : EN;
    }

    private static Map<String, String> load(String catalog) {
        String path = "i18n/messages_" + catalog + ".properties";
        try (var input = Messages.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path);
            var properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            var values = new LinkedHashMap<String, String>();
            for (String name : properties.stringPropertyNames())
                values.put(name, properties.getProperty(name));
            return Collections.unmodifiableMap(values);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load " + path, e);
        }
    }
}
