package fr.openobject.ai.syleks.session.repository;

import fr.openobject.ai.syleks.session.model.*;

public interface SessionRepository {
    Session createSession(
            String projectId,
            String parentId,
            String title,
            String provider,
            String model,
            String summary);

    java.util.Optional<Session> findSession(String id);

    java.util.List<Session> listSessions(String projectId);

    void updateSession(
            String id,
            String title,
            String provider,
            String model,
            SessionStatus status,
            String summary);

    void deleteSession(String id);

    void account(String id, String accountId);

    void recoverSession(String id);
}
