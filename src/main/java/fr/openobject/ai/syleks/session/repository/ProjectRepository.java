package fr.openobject.ai.syleks.session.repository;

import fr.openobject.ai.syleks.session.model.*;

public interface ProjectRepository {
    Project upsertProject(String rootPath, String remote, String name);
}
