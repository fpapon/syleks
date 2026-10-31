/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.model.ChatMessage;
import fr.openobject.ai.syleks.session.model.*;
import fr.openobject.ai.syleks.session.repository.*;
import fr.openobject.ai.syleks.session.storage.SqliteDatabase;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.util.*;

@DefaultScoped
public class ConversationService {
    private final MessageRepository messages;
    private final MessagePartRepository parts;
    private final SqliteDatabase database;
    private final JsonMapper json;

    public ConversationService(
            MessageRepository messages,
            MessagePartRepository parts,
            SqliteDatabase database,
            JsonMapper json) {
        this.messages = messages;
        this.parts = parts;
        this.database = database;
        this.json = json;
    }

    public Message add(String session, String role, String text, MessageStatus status) {
        return database.transaction(
                () -> {
                    var message = messages.addMessage(session, role, status);
                    parts.addPart(message.id(), PartType.TEXT, json.toString(Map.of("text", text)));
                    return message;
                });
    }

    public Message instructions(String session, String agentPrompt, String projectInstructions) {
        return database.transaction(
                () -> {
                    var message = messages.addMessage(session, "system", MessageStatus.COMPLETE);
                    parts.addPart(
                            message.id(),
                            PartType.TEXT,
                            json.toString(
                                    Map.of(
                                            "text",
                                            agentPrompt + projectInstructions,
                                            "projectInstructions",
                                            projectInstructions)));
                    return message;
                });
    }

    public java.util.Optional<SummaryRepository.Summary> summary(String session) {
        return new SummaryRepository(database).find(session);
    }

    public List<Message> messages(String session) {
        return messages.listMessages(session);
    }

    public List<MessagePart> parts(String message) {
        return parts.listParts(message);
    }

    public Map<?, ?> payload(MessagePart part) {
        if (part.schemaVersion() != 1)
            throw new IllegalStateException(
                    "Unsupported message part schema " + part.schemaVersion());
        return json.fromString(Map.class, part.payloadJson());
    }

    public void part(String message, PartType type, Object value) {
        parts.addPart(message, type, json.toString(value));
    }

    public void text(String message, String value) {
        database.transaction(
                () -> {
                    var text =
                            parts(message).stream()
                                    .filter(p -> p.type() == PartType.TEXT)
                                    .findFirst()
                                    .orElseThrow();
                    parts.updatePart(text.id(), json.toString(Map.of("text", value)));
                    return null;
                });
    }

    public void status(String message, MessageStatus value) {
        messages.updateMessage(message, value);
    }

    public List<ChatMessage> history(String session) {
        return history(session, true);
    }

    public List<ChatMessage> history(String session, boolean debugLlm) {
        var result = new ArrayList<ChatMessage>();
        for (var message : messages(session)) {
            if (message.role().equals("system"))
                continue; // Instructions belong to model context, not the transcript.
            var text = new StringBuilder();
            var visual = new StringBuilder();
            var reasoning = new StringBuilder();
            for (var part : parts(message.id())) {
                var payload = payload(part);
                if (part.type() == PartType.TEXT) {
                    var value = Objects.toString(payload.get("text"), "");
                    text.append(value);
                    visual.append(value);
                }
                if (part.type() == PartType.REASONING)
                    reasoning.append(Objects.toString(payload.get("text"), ""));
                if (debugLlm && part.type() == PartType.TOOL_CALL)
                    visual.append("\nTool · ").append(payload.get("name"));
                if (part.type() == PartType.TOOL_RESULT) {
                    text.append("\n").append(payload.get("content"));
                    if (debugLlm)
                        visual.append("\n")
                                .append(
                                        payload.containsKey("display")
                                                ? payload.get("display")
                                                : payload.get("content"));
                }
            }
            String display =
                    (reasoning.isEmpty() ? "" : "### Reasoning\n\n" + reasoning + "\n\n") + visual;
            if (!debugLlm && display.isBlank() && message.status() == MessageStatus.COMPLETE)
                continue;
            if (message.status() == MessageStatus.INTERRUPTED
                    || message.status() == MessageStatus.ERROR)
                display += "\n[" + message.status() + "]";
            result.add(
                    new ChatMessage(
                            message.role().equals("tool") ? "assistant" : message.role(),
                            text.toString(),
                            message.createdAt().substring(11, 16),
                            display));
        }
        return List.copyOf(result);
    }
}
