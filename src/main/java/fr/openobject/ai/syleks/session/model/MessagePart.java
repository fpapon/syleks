package fr.openobject.ai.syleks.session.model;

public record MessagePart(
        String id,
        String messageId,
        int position,
        PartType type,
        String payloadJson,
        int schemaVersion) {}
