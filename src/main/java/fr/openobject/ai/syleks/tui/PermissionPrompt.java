package fr.openobject.ai.syleks.tui;

import fr.openobject.ai.syleks.model.PermissionRequest;

/** UI-owned focus and scrolling. New requests always start on the single-use option. */
final class PermissionPrompt {
    private PermissionRequest request;
    private int selected;
    private boolean expanded;
    private int scroll;

    void sync(PermissionRequest next) {
        if (request == null || next == null || request.id() != next.id()) {
            selected = 0;
            expanded = false;
            scroll = 0;
        }
        request = next;
    }

    void move(int direction) {
        if (request == null) return;
        do {
            selected = Math.floorMod(selected + direction, 3);
        } while (selected == 1 && !request.alwaysAllowed());
    }

    void scroll(int delta) {
        scroll = (int) Math.clamp((long) scroll + delta, 0, 1_000_000);
    }

    void clampScroll(int max) {
        scroll = Math.min(scroll, max);
    }

    void toggleExpanded() {
        expanded = !expanded;
    }

    int selected() {
        return selected;
    }

    boolean expanded() {
        return expanded;
    }

    int scroll() {
        return scroll;
    }

    PermissionRequest request() {
        return request;
    }

    String choice() {
        return switch (selected) {
            case 1 -> "always";
            case 2 -> "reject";
            default -> "once";
        };
    }
}
