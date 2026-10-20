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
package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.app.ConfigInitWizard;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import org.junit.jupiter.api.Test;

class ConfigurationModalTest {
    @Test
    void navigatesEditsValidatesAndRequiresSeparateSaveAndRestartChoices() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var form = new ConfigInitWizard(mapper.instance()).form(null);
            var modal = new ConfigurationModal(form);
            for (int key : "team".codePoints().toArray()) modal.key(key);
            modal.key('\r');
            assertEquals("team", form.value(0));
            assertEquals(1, modal.field());
            modal.sequence("Z");
            assertEquals(0, modal.field());
            modal.key('\r');
            modal.key('\r');
            assertEquals(2, modal.field());
            for (int key : "bad-url".codePoints().toArray()) modal.key(key);
            modal.key('\r');
            assertEquals(2, modal.field());
            assertFalse(modal.error().isEmpty());
            modal.sequence("Z");
            modal.key('\r'); // Invalid URL is retained when going back.
            modal.key(127);
            for (int key : "http://localhost:8000/v1".codePoints().toArray()) modal.key(key);
            modal.key('\r');
            assertEquals(3, modal.field());
            for (int i = 3; i < ConfigInitWizard.FIELDS.size(); i++) modal.key('\r');
            assertEquals(ConfigurationModal.Stage.REVIEW, modal.stage());
            assertEquals(ConfigurationModal.Action.SAVE, modal.key('\r'));
            modal.saving();
            assertEquals(ConfigurationModal.Action.NONE, modal.key(27));
            modal.saved();
            assertEquals(ConfigurationModal.Stage.SAVED, modal.stage());
            assertEquals(ConfigurationModal.Action.RESTART, modal.key('\r'));
        }
    }

    @Test
    void masksExistingSecretsClipsSafelyAndOffersLaterOrCancellation() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var form = new ConfigInitWizard(mapper.instance()).form(null);
            form.value(3, "sensitive-api-key");
            var modal = new ConfigurationModal(form);
            for (int i = 0; i < 3; i++) modal.key('\r');
            var screen = modal.paint(new TerminalCanvas(100, 30));
            assertFalse(text(screen.canvas()).contains("sensitive-api-key"));
            assertTrue(text(screen.canvas()).contains("••••"));
            for (int width : new int[] {1, 34, 35, 60, 100})
                for (int height : new int[] {1, 14, 15, 30}) {
                    assertDoesNotThrow(() -> modal.paint(new TerminalCanvas(width, height)));
                }
            modal.saved();
            modal.key(9);
            assertEquals(ConfigurationModal.Action.CLOSE, modal.key('\r'));
            var cancelled = new ConfigurationModal(form);
            assertEquals(ConfigurationModal.Action.CLOSE, cancelled.key(27));
            assertEquals(ConfigurationModal.Stage.CLOSED, cancelled.stage());
        }
    }

    @Test
    void bracketedPasteCannotSaveRestartOrInjectNewlines() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var modal = new ConfigurationModal(new ConfigInitWizard(mapper.instance()).form(null));
            modal.sequence("200~");
            for (int key : "team\n\r\t".codePoints().toArray()) modal.key(key);
            assertEquals(0, modal.field());
            assertEquals("team", modal.input());
            modal.sequence("201~");
            modal.key('\r');
            assertEquals(1, modal.field());
            modal.saved();
            modal.sequence("200~");
            assertEquals(ConfigurationModal.Action.NONE, modal.key('\r'));
            assertEquals(ConfigurationModal.Stage.SAVED, modal.stage());
        }
    }

    @Test
    void failedSaveKeepsTheFormForRetryWithoutRequestingARestart() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var modal = new ConfigurationModal(new ConfigInitWizard(mapper.instance()).form(null));
            modal.saving();
            modal.failed("Disk is full");
            assertEquals(ConfigurationModal.Stage.REVIEW, modal.stage());
            assertTrue(
                    text(modal.paint(new TerminalCanvas(100, 30)).canvas())
                            .contains("Disk is full"));
            assertEquals(ConfigurationModal.Action.SAVE, modal.key('\r'));
        }
    }

    private String text(TerminalCanvas canvas) {
        var result = new StringBuilder();
        for (int y = 0; y < canvas.height(); y++) {
            for (int x = 0; x < canvas.width(); x++) result.append(canvas.at(x, y).glyph());
            result.append('\n');
        }
        return result.toString();
    }
}
