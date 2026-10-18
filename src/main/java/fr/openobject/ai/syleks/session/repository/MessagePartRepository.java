package fr.openobject.ai.syleks.session.repository;

import fr.openobject.ai.syleks.session.model.*;

public interface MessagePartRepository {
    MessagePart addPart(String messageId, PartType type, String payloadJson);

    java.util.List<MessagePart> listParts(String messageId);

    void updatePart(String id, String payloadJson);
}
