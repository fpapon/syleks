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

import org.junit.jupiter.api.Test;

class EditorTest {
    @Test
    void editsUnicodeWithoutSplittingSurrogates() {
        var editor = new Editor();
        editor.insert("A🧪B");
        editor.left();
        editor.backspace();
        assertEquals("AB", editor.text());
        editor.delete();
        assertEquals("A", editor.text());
    }

    @Test
    void multilinePasteDoesNotTriggerSubmission() {
        var editor = new Editor();
        editor.insert("one\ntwo\rthree\tend");
        assertEquals("one\ntwo\nthree end", editor.text());
    }

    @Test
    void recallsMultilinePromptsAndRestoresDraftWithUnicodeCursor() {
        var editor = new Editor();
        editor.previousPrompt();
        editor.nextPrompt();
        assertEquals("", editor.text());
        editor.remember("first\n🧪 second line");
        editor.remember("latest");
        editor.setText("draft 🧪 end");
        editor.left();
        int draftCaret = editor.caret();
        editor.previousPrompt();
        assertEquals("latest", editor.text());
        assertEquals(editor.text().length(), editor.caret());
        editor.previousPrompt();
        editor.previousPrompt();
        assertEquals("first\n🧪 second line", editor.text());
        editor.nextPrompt();
        assertEquals("latest", editor.text());
        editor.nextPrompt();
        editor.nextPrompt();
        assertEquals("draft 🧪 end", editor.text());
        assertEquals(draftCaret, editor.caret());
    }

    @Test
    void editingRecalledPromptDoesNotChangeTheStoredEntry() {
        var editor = new Editor();
        editor.remember("original");
        editor.previousPrompt();
        editor.insert(" edited");
        editor.nextPrompt();
        assertEquals("", editor.text());
        editor.previousPrompt();
        assertEquals("original", editor.text());
        editor.remember("original edited");
        editor.clear();
        editor.previousPrompt();
        assertEquals("original edited", editor.text());
        editor.previousPrompt();
        assertEquals("original", editor.text());
    }

    @Test
    void newDraftResetsNavigationAndHistoryIsBoundedWithoutConsecutiveDuplicates() {
        var editor = new Editor();
        editor.remember(" ");
        for (int i = 0; i < 501; i++) {
            editor.remember("prompt " + i);
            editor.remember("prompt " + i);
        }
        assertEquals(500, editor.promptHistory().size());
        assertEquals("prompt 1", editor.promptHistory().getFirst());
        editor.previousPrompt();
        editor.setText("another session draft");
        editor.previousPrompt();
        assertEquals("prompt 500", editor.text());
        editor.nextPrompt();
        assertEquals("another session draft", editor.text());
        var restarted = new Editor();
        restarted.restorePromptHistory(editor.promptHistory());
        restarted.previousPrompt();
        assertEquals("prompt 500", restarted.text());
    }
}
