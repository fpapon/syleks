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

import java.util.HashMap;
import java.util.Map;

/** Drafts survive session changes in this process; never written to SQLite implicitly. */
final class SessionViewState {
    private final Map<String, String> drafts = new HashMap<>();
    private String active = "";
    private int scroll;

    Map<String, String> snapshot(String currentDraft) {
        var copy = new HashMap<>(drafts);
        copy.put(active, currentDraft);
        return Map.copyOf(copy);
    }

    void restore(String id, Map<String, String> values) {
        drafts.clear();
        drafts.putAll(values);
        active = id == null ? "" : id;
        scroll = 0;
    }

    void identifyInitial(String id) {
        if (active.isEmpty() && id != null) active = id;
    }

    void scroll(int value) {
        scroll = value;
    }

    int scroll() {
        return scroll;
    }

    void forget(String id, Editor editor) {
        drafts.remove(id);
        if (active.equals(id)) {
            active = "";
            editor.setText("");
            scroll = 0;
        }
    }

    void switchTo(String id, Editor editor) {
        drafts.put(active, editor.text());
        active = id == null ? "" : id;
        editor.setText(drafts.getOrDefault(active, ""));
        scroll = 0;
    }
}
