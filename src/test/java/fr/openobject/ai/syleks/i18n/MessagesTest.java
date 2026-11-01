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

import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagesTest {
    @Test
    void fallsBackFromTheActiveCatalogThroughEnglishThenFrench() {
        var french = Messages.forCatalog("fr_FR");
        assertEquals("Syleks · Aide", french.get("help.title"));
        assertNotEquals(Messages.forCatalog("en_US").get("help.title"), french.get("help.title"));

        var missingFrench =
                Messages.of(
                        Map.of(),
                        Map.of("only.english", "English"),
                        Map.of("only.french", "Français"));
        assertEquals("English", missingFrench.get("only.english"));
        assertEquals("Français", missingFrench.get("only.french"));
        assertEquals("missing.key", missingFrench.get("missing.key"));
        assertEquals(
                "Hello world",
                Messages.of(Map.of("greet", "Hello {0}"), Map.of(), Map.of())
                        .get("greet", "world"));
    }

    @Test
    void shipsTheSameKeysInBothCatalogs() {
        assertEquals(Messages.englishKeys(), Messages.frenchKeys());
        assertFalse(Messages.englishKeys().isEmpty());
        assertEquals(
                "Reply in French (fr_FR) unless the user writes in another language or asks you not to.",
                Messages.forCatalog("en_US").get("context.reply", "French", "fr_FR"));
    }
}
