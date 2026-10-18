package fr.openobject.ai.syleks.session.model;

public record Project(
        String id,
        String rootPath,
        String gitRemote,
        String name,
        String createdAt,
        String updatedAt) {}
