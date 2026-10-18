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

    void switchTo(String id, Editor editor) {
        drafts.put(active, editor.text());
        active = id == null ? "" : id;
        editor.setText(drafts.getOrDefault(active, ""));
        scroll = 0;
    }
}
