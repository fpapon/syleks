package fr.openobject.ai.syleks.session.repository;

import fr.openobject.ai.syleks.session.model.*;

public interface MessageRepository {
    Message addMessage(String sessionId, String role, MessageStatus status);

    java.util.List<Message> listMessages(String sessionId);

    void updateMessage(String id, MessageStatus status);
}
