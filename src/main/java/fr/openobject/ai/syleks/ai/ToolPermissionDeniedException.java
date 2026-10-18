package fr.openobject.ai.syleks.ai;

import java.io.IOException;

public final class ToolPermissionDeniedException extends IOException {
    public ToolPermissionDeniedException(String message) {
        super(message);
    }
}
