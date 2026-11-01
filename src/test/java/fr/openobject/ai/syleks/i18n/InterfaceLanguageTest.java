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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterfaceLanguageTest {
    @TempDir Path home;

    @Test
    void normalizesOperatingSystemLocalesAndSkipsPosix() {
        assertEquals("en_US", resolve("system", Map.of("LANG", "en_US.UTF-8")).tag());
        assertEquals("fr_FR", resolve("system", Map.of("LANG", "fr_FR.UTF-8")).tag());
        assertEquals("fr_FR", resolve("system", Map.of("LANG", "fr")).tag());
        assertEquals("en_US", resolve("system", Map.of("LANG", "en")).tag());
        assertEquals(
                "en_US",
                resolve("system", Map.of("LC_ALL", "en_US.UTF-8", "LANG", "fr_FR.UTF-8")).tag());
        assertEquals(
                "fr_FR",
                resolve(
                                "system",
                                Map.of(
                                        "LC_ALL",
                                        "C",
                                        "LC_MESSAGES",
                                        "POSIX",
                                        "LANG",
                                        "fr_FR.UTF-8"))
                        .tag());
        assertEquals("de_DE", resolve("system", Map.of("LANG", "de_DE.UTF-8@euro")).tag());
        assertEquals("en_US", resolve("system", Map.of("LANG", "de_DE.UTF-8")).catalog());
        assertEquals("fr_FR", resolve("fr-FR", Map.of("LANG", "en_US.UTF-8")).tag());
        assertEquals("fr_FR", resolve("fr_fr", Map.of()).catalog());
        assertEquals("en_US", resolve("en-us", Map.of()).tag());
    }

    @Test
    void rejectsMalformedTagsAndIgnoresABadSavedValueBeforeStartup() throws Exception {
        assertFalse(InterfaceLanguage.wellFormed(""));
        assertFalse(InterfaceLanguage.wellFormed("!!!"));
        assertFalse(InterfaceLanguage.wellFormed("en_US.UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> resolve("!!!", Map.of()));
        Files.createDirectories(home.resolve(".config/syleks"));
        Files.writeString(
                home.resolve(".config/syleks/agent-config.json"),
                "{\"model\":1,\"agent\":{\"language\":\"no\"},\"language\":\"fr_FR\"}");
        assertEquals("fr_FR", InterfaceLanguage.load(home, Map.of("LANG", "en_US")).tag());
        Files.writeString(
                home.resolve(".config/syleks/agent-config.json"), "{\"language\":\"not a tag\"}");
        assertEquals("en_US", InterfaceLanguage.load(home, Map.of("LANG", "en_US.UTF-8")).tag());
        Files.writeString(home.resolve(".config/syleks/agent-config.json"), "{");
        assertEquals("de_DE", InterfaceLanguage.load(home, Map.of("LANG", "de_DE")).tag());
    }

    @Test
    void namesTheLanguageInTheCatalogLocale() {
        var french = InterfaceLanguage.resolve("fr_FR", Map.of());
        assertEquals("français", french.displayName().toLowerCase(Locale.ROOT));
        var german = InterfaceLanguage.resolve("de_DE", Map.of());
        assertEquals("German", german.displayName());
    }

    private static InterfaceLanguage.Selection resolve(String configured, Map<String, String> env) {
        return InterfaceLanguage.resolve(configured, env);
    }
}
