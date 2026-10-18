package fr.openobject.ai.syleks.model;

/** Immutable permission prompt, identified independently of generation and render cycles. */
public record PermissionRequest(long id, String description, String details, boolean alwaysAllowed) {
}
