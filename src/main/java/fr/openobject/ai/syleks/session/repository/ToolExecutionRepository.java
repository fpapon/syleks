package fr.openobject.ai.syleks.session.repository;

import fr.openobject.ai.syleks.session.model.*;

public interface ToolExecutionRepository {
    void saveExecution(ToolExecution execution);

    java.util.List<ToolExecution> listExecutions(String sessionId);
}
