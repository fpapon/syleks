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

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import fr.openobject.ai.syleks.session.model.*;
import fr.openobject.ai.syleks.session.repository.*;
import fr.openobject.ai.syleks.session.storage.*;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Versioned, bounded data archives. Imports remap identity and never restore execution authority. */
final class SessionArchive {
    private static final int LIMIT = 32 * 1024 * 1024;
    private static final ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(64)
                                                    .maxStringLength(LIMIT)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> USAGE_COLUMNS =
            List.of(
                    "id",
                    "request_id",
                    "session_id",
                    "provider_id",
                    "model_id",
                    "input_tokens",
                    "output_tokens",
                    "total_tokens",
                    "cached_input_tokens",
                    "reasoning_output_tokens",
                    "duration_ms",
                    "first_token_latency_ms",
                    "tokens_per_second",
                    "provider_duration_ns",
                    "prompt_duration_ns",
                    "generation_duration_ns",
                    "context_window",
                    "usage_source",
                    "status",
                    "created_at",
                    "updated_at");
    private final SessionService service;
    private final ProjectService projects;
    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final MessagePartRepository parts;
    private final ToolExecutionRepository tools;
    private final SqliteDatabase database;
    private final JsonMapper json;

    SessionArchive(
            SessionService service,
            ProjectService projects,
            SessionRepository sessions,
            MessageRepository messages,
            MessagePartRepository parts,
            ToolExecutionRepository tools,
            SqliteDatabase database,
            JsonMapper json) {
        this.service = service;
        this.projects = projects;
        this.sessions = sessions;
        this.messages = messages;
        this.parts = parts;
        this.tools = tools;
        this.database = database;
        this.json = json;
    }

    String export(String id) {
        service.load(id);
        var ids = tree(id);
        var leases = new ArrayList<SessionLocks.Lease>();
        try {
            for (var key : ids.stream().sorted().toList()) leases.add(service.acquire(key));
            return database.transaction(
                    () -> {
                        var root = document(id);
                        root.put("schemaVersion", 2);
                        root.put("projectRoot", projects.current().rootPath());
                        var children = root.putArray("children");
                        for (var child : ids) if (!child.equals(id)) children.add(document(child));
                        String content = root.toString();
                        if (content.getBytes(StandardCharsets.UTF_8).length > LIMIT)
                            throw invalid("Archive exceeds 32 MiB");
                        return content;
                    });
        } finally {
            Collections.reverse(leases);
            leases.forEach(SessionLocks.Lease::close);
        }
    }

    private List<String> tree(String id) {
        var result = new ArrayList<String>();
        result.add(id);
        var all = sessions.listSessions(projects.currentId());
        for (int i = 0; i < result.size(); i++) {
            String parent = result.get(i);
            for (var session : all)
                if (parent.equals(session.parentSessionId())
                        && service.tasks().find(session.id()).isPresent()
                        && !result.contains(session.id())) result.add(session.id());
            if (result.size() > 128) throw invalid("Archive exceeds 128 sessions");
        }
        return List.copyOf(result);
    }

    private ObjectNode document(String id) {
        try {
            var node = (ObjectNode) JSON.readTree(service.exportOne(id));
            service.summaries()
                    .find(id)
                    .ifPresent(s -> node.put("summaryThroughPosition", s.throughPosition()));
            node.set("todos", JSON.readTree(service.todos().read(id)));
            var usage = node.putArray("usage");
            database.read(
                    c -> {
                        try (var s =
                                c.prepareStatement(
                                        "SELECT * FROM llm_usage WHERE session_id=? ORDER BY created_at,request_id")) {
                            s.setString(1, id);
                            try (var r = s.executeQuery()) {
                                while (r.next()) {
                                    var row = usage.addObject();
                                    for (String column : USAGE_COLUMNS)
                                        row.set(column, JSON.valueToTree(r.getObject(column)));
                                }
                            }
                        }
                        return null;
                    });
            var changes = node.putArray("fileChanges");
            long bytes = 0;
            for (var change : service.fileChanges().list(id)) {
                bytes +=
                        (change.before() == null ? 0 : change.before().length)
                                + (change.after() == null ? 0 : change.after().length);
                if (bytes > LIMIT / 2) throw invalid("File history exceeds archive limit");
                var value = changes.addObject();
                value.put("id", change.id());
                value.put("group", change.group());
                value.put("call", change.call());
                value.put("path", change.path());
                value.put(
                        "before",
                        change.before() == null
                                ? null
                                : Base64.getEncoder().encodeToString(change.before()));
                value.put(
                        "after",
                        change.after() == null
                                ? null
                                : Base64.getEncoder().encodeToString(change.after()));
                value.put("state", change.state());
                value.put("created", change.created());
            }
            return node;
        } catch (java.io.IOException e) {
            throw invalid("Unable to serialize archive");
        }
    }

    Session importArchive(String content) {
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > LIMIT)
            throw invalid("Archive exceeds 32 MiB");
        final JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (java.io.IOException e) {
            throw invalid("Expected valid JSON without duplicate fields");
        }
        if (root == null || !root.isObject()) throw invalid("Expected an archive object");
        int version = integer(root, "schemaVersion", 1, 2);
        if (version == 2
                ? !projects.current().rootPath().equals(text(root, "projectRoot", 8192))
                : !projects.currentId().equals(text(root.path("session"), "projectId", 36)))
            throw invalid("Archive belongs to another project");
        var documents = new ArrayList<JsonNode>();
        documents.add(root);
        if (root.has("children")) {
            for (var child : array(root, "children", 127)) documents.add(child);
        }
        var identities = new HashSet<String>();
        var sessionIds = new HashSet<String>();
        var messageIds = new HashSet<String>();
        for (var doc : documents) {
            String id = uuid(doc.path("session"), "id");
            unique(identities, id);
            sessionIds.add(id);
        }
        String sourceProject = text(root.path("session"), "projectId", 36);
        for (var doc : documents) validate(doc, identities, messageIds, sourceProject);
        String originalRoot = uuid(root.path("session"), "id");
        var reachable = new HashSet<String>();
        reachable.add(originalRoot);
        for (int round = 0; round < documents.size(); round++)
            for (var doc : documents) {
                String parent = nullable(doc.path("session"), "parentSessionId");
                if (parent != null && reachable.contains(parent))
                    reachable.add(uuid(doc.path("session"), "id"));
            }
        if (!reachable.equals(sessionIds)) throw invalid("Disconnected or cyclic child tree");
        for (var doc : documents) {
            String key = uuid(doc.path("session"), "id");
            if (!key.equals(originalRoot) && !doc.path("session").has("task"))
                throw invalid("Child session requires task metadata");
            if (doc.path("session").has("task")) {
                var task = doc.path("session").get("task");
                String expectedRoot =
                        root.path("session").has("task")
                                ? nullable(root.path("session").path("task"), "rootSessionId")
                                : originalRoot;
                if (!Objects.equals(expectedRoot, nullable(task, "rootSessionId")))
                    throw invalid("Task root mismatch");
                String parent = nullable(task, "parentSessionId");
                if (!Objects.equals(parent, nullable(doc.path("session"), "parentSessionId")))
                    throw invalid("Task parent mismatch");
                if (!key.equals(originalRoot) && !sessionIds.contains(parent))
                    throw invalid("Missing child parent");
                String reference = nullable(task, "parentMessageId");
                if (reference != null && sessionIds.contains(parent)) {
                    var parentDoc =
                            documents.stream()
                                    .filter(d -> uuid(d.path("session"), "id").equals(parent))
                                    .findFirst()
                                    .orElseThrow();
                    boolean found = false;
                    for (var m : parentDoc.path("messages"))
                        if (m.path("id").asText().equals(reference)) found = true;
                    if (!found) throw invalid("Task parent message belongs to another session");
                }
            }
        }
        return database.transaction(
                () -> {
                    var mapping = new HashMap<String, String>();
                    for (var id : identities) mapping.put(id, UUID.randomUUID().toString());
                    // Repository-generated IDs are allocated first; import never overwrites an
                    // existing row.
                    for (var doc : documents) {
                        var meta = doc.path("session");
                        String old = uuid(meta, "id");
                        var copy =
                                sessions.createSession(
                                        projects.currentId(),
                                        null,
                                        text(meta, "title", 240),
                                        text(meta, "provider", 240),
                                        text(meta, "model", 240),
                                        nullable(meta, "summary"));
                        mapping.put(old, copy.id());
                    }
                    for (var doc : documents) {
                        String old = uuid(doc.path("session"), "id"), target = mapping.get(old);
                        for (var m : doc.path("messages")) {
                            var status = MessageStatus.valueOf(text(m, "status", 30));
                            var copy =
                                    messages.addMessage(
                                            target,
                                            text(m, "role", 20),
                                            status == MessageStatus.GENERATING
                                                    ? MessageStatus.INTERRUPTED
                                                    : status);
                            mapping.put(uuid(m, "id"), copy.id());
                            timestamps(
                                    "messages",
                                    copy.id(),
                                    text(m, "createdAt", 64),
                                    text(m, "updatedAt", 64));
                        }
                    }
                    for (var doc : documents) install(doc, mapping, originalRoot);
                    return service.load(mapping.get(originalRoot));
                });
    }

    private void validate(
            JsonNode doc, Set<String> identities, Set<String> allMessages, String project) {
        if (!doc.isObject()) throw invalid("Invalid session document");
        integer(doc, "schemaVersion", 1, 2);
        var meta = doc.path("session");
        if (!project.equals(text(meta, "projectId", 36))) throw invalid("Mixed archive projects");
        text(meta, "title", 240);
        text(meta, "provider", 240);
        text(meta, "model", 240);
        timestamp(meta, "createdAt");
        timestamp(meta, "updatedAt");
        SessionStatus.valueOf(text(meta, "status", 30));
        String summary = nullable(meta, "summary");
        if (summary != null && summary.getBytes(StandardCharsets.UTF_8).length > 16000)
            throw invalid("Summary exceeds limit");
        if (meta.hasNonNull("parentSessionId")) uuid(meta, "parentSessionId");
        var calls = new HashMap<String, JsonNode>();
        var results = new HashSet<String>();
        var localMessages = new HashSet<String>();
        int position = 0;
        for (var message : array(doc, "messages", 100000)) {
            String id = uuid(message, "id");
            unique(identities, id);
            allMessages.add(id);
            localMessages.add(id);
            if (integer(message, "position", 0, 100000) != position++)
                throw invalid("Messages must have consecutive positions");
            String role = text(message, "role", 20);
            if (!Set.of("system", "user", "assistant", "tool").contains(role))
                throw invalid("Invalid message role");
            MessageStatus.valueOf(text(message, "status", 30));
            timestamp(message, "createdAt");
            timestamp(message, "updatedAt");
            int partPosition = 0;
            for (var part : array(message, "parts", 10000)) {
                unique(identities, uuid(part, "id"));
                if (integer(part, "position", 0, 10000) != partPosition++
                        || integer(part, "schemaVersion", 1, 1) != 1)
                    throw invalid("Invalid part order or schema");
                var type = PartType.valueOf(text(part, "type", 32));
                var payload = part.path("payload");
                if (!payload.isObject()) throw invalid("Part payload must be an object");
                if (type == PartType.TEXT || type == PartType.REASONING) {
                    if (!payload.path("text").isTextual()) throw invalid("Text part requires text");
                }
                if (type == PartType.TOOL_CALL) {
                    if (!role.equals("assistant"))
                        throw invalid("Tool call must belong to assistant");
                    String call = text(payload, "callId", 240);
                    text(payload, "name", 100);
                    if (!payload.path("arguments").isObject()
                            || calls.put(id + "/" + call, payload) != null)
                        throw invalid("Invalid or duplicate tool call");
                }
                if (type == PartType.TOOL_RESULT) {
                    if (!role.equals("tool") || !payload.path("content").isTextual())
                        throw invalid("Invalid tool result");
                    String call = text(payload, "callId", 240);
                    String reference = nullable(payload, "callMessageId");
                    if (reference == null) {
                        var matches =
                                calls.keySet().stream()
                                        .filter(k -> k.endsWith("/" + call))
                                        .toList();
                        if (matches.size() != 1) throw invalid("Ambiguous legacy tool result");
                        reference = matches.getFirst().substring(0, 36);
                    }
                    String key = reference + "/" + call;
                    if (!calls.containsKey(key)
                            || !results.add(key)
                            || !text(calls.get(key), "name", 100)
                                    .equals(text(payload, "name", 100)))
                        throw invalid("Unpaired or duplicate tool result");
                    if (payload.has("childSessionIds"))
                        for (var child : array(payload, "childSessionIds", 128)) {
                            if (!child.isTextual()) throw invalid("Invalid child ID");
                            UUID.fromString(child.asText());
                        }
                }
            }
        }
        var executionKeys = new HashSet<String>();
        for (var execution : array(doc, "toolExecutions", 100000)) {
            unique(identities, uuid(execution, "id"));
            String message = uuid(execution, "messageId");
            String executionKey = message + "/" + text(execution, "toolCallId", 240);
            if (!executionKeys.add(executionKey))
                throw invalid("Duplicate execution for a tool call");
            var call = calls.get(executionKey);
            if (!localMessages.contains(message)
                    || call == null
                    || !text(execution, "name", 100).equals(call.path("name").asText())
                    || !execution.path("parameters").equals(call.path("arguments")))
                throw invalid("Tool execution does not match its call");
            ToolStatus.valueOf(text(execution, "status", 30));
            text(execution, "authorization", 100);
            timestamp(execution, "startedAt");
            if (execution.hasNonNull("endedAt")) timestamp(execution, "endedAt");
        }
        if (doc.has("summaryThroughPosition")) {
            int through = integer(doc, "summaryThroughPosition", 0, Math.max(0, position - 1));
            if (summary == null || summary.isBlank())
                throw invalid("Summary boundary without summary");
            var prefix = new HashSet<String>();
            for (var m : doc.path("messages"))
                if (m.path("position").asInt() <= through) prefix.add(m.path("id").asText());
            for (var m : doc.path("messages"))
                if (m.path("position").asInt() > through)
                    for (var p : m.path("parts"))
                        if (p.path("type").asText().equals("TOOL_RESULT")
                                && prefix.contains(
                                        p.path("payload").path("callMessageId").asText()))
                            throw invalid("Summary splits tool pair");
        }
        if (meta.has("task")) {
            var task = meta.get("task");
            text(task, "agent", 64);
            text(task, "callId", 240);
            if (task.hasNonNull("rootSessionId")) uuid(task, "rootSessionId");
            if (task.hasNonNull("parentSessionId")) uuid(task, "parentSessionId");
            if (task.hasNonNull("parentMessageId")) uuid(task, "parentMessageId");
            AgentTask.Status.valueOf(text(task, "status", 30));
            integer(task, "rounds", 0, 1000000);
            for (var actor : array(task, "actors", 16))
                if (!actor.isTextual() || !actor.asText().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
                    throw invalid("Invalid task actor");
        }
        if (doc.has("todos")) {
            var todos = array(doc, "todos", 100);
            var todoIds = new HashSet<String>();
            for (var todo : todos) {
                if (!todoIds.add(text(todo, "id", 64))) throw invalid("Duplicate todo ID");
                text(todo, "content", 1000);
                if (!Set.of("pending", "in_progress", "completed", "cancelled")
                                .contains(text(todo, "status", 20))
                        || !Set.of("low", "medium", "high").contains(text(todo, "priority", 20)))
                    throw invalid("Invalid todo");
            }
        }
        if (doc.has("usage"))
            for (var row : array(doc, "usage", 100000)) {
                unique(identities, text(row, "request_id", 100));
                if (!row.path("session_id").asText().equals(uuid(meta, "id")))
                    throw invalid("Usage belongs to another session");
                text(row, "provider_id", 240);
                text(row, "model_id", 240);
                fr.openobject.ai.syleks.usage.TokenUsage.Source.valueOf(
                        text(row, "usage_source", 30));
                fr.openobject.ai.syleks.usage.LlmUsageEvent.Status.valueOf(text(row, "status", 30));
                for (var column : USAGE_COLUMNS)
                    if (!Set.of(
                                    "id",
                                    "request_id",
                                    "session_id",
                                    "provider_id",
                                    "model_id",
                                    "usage_source",
                                    "status")
                            .contains(column)) {
                        var value = row.path(column);
                        if (!value.isNull()
                                && (!value.isNumber()
                                        || value.asDouble() < 0
                                        || !Double.isFinite(value.asDouble())
                                        || !column.equals("tokens_per_second")
                                                && (!value.isIntegralNumber()
                                                        || !value.canConvertToLong())))
                            throw invalid("Invalid usage metric");
                    }
            }
        if (doc.has("fileChanges"))
            for (var change : array(doc, "fileChanges", 100000)) {
                unique(identities, uuid(change, "id"));
                uuid(change, "group");
                text(change, "call", 240);
                text(change, "path", 8192);
                timestamp(change, "created");
                blob(change, "before");
                blob(change, "after");
            }
    }

    private void install(JsonNode doc, Map<String, String> mapping, String originalRoot) {
        var meta = doc.path("session");
        String old = uuid(meta, "id"), id = mapping.get(old);
        String parent = mapping.get(nullable(meta, "parentSessionId"));
        boolean interrupted = meta.path("status").asText().equals("ACTIVE");
        for (var m : doc.path("messages"))
            interrupted |= m.path("status").asText().equals("GENERATING");
        for (var t : doc.path("toolExecutions"))
            interrupted |= Set.of("PENDING", "RUNNING").contains(t.path("status").asText());
        for (var u : doc.path("usage")) interrupted |= u.path("status").asText().equals("RUNNING");
        String importedStatus = interrupted ? "INTERRUPTED" : meta.path("status").asText();
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE sessions SET parent_session_id=?,status=? WHERE id=?")) {
                        s.setString(1, parent);
                        s.setString(2, importedStatus);
                        s.setString(3, id);
                        s.executeUpdate();
                    }
                    return null;
                });
        timestamps("sessions", id, text(meta, "createdAt", 64), text(meta, "updatedAt", 64));
        for (var message : doc.path("messages"))
            for (var part : message.path("parts")) {
                var payload = part.path("payload").deepCopy();
                if (part.path("type").asText().equals("TOOL_RESULT")) {
                    var object = (ObjectNode) payload;
                    if (payload.hasNonNull("callMessageId"))
                        object.put(
                                "callMessageId",
                                mapping.get(payload.get("callMessageId").asText()));
                    if (payload.has("childSessionIds")) {
                        var children = object.putArray("childSessionIds");
                        for (var child : part.path("payload").path("childSessionIds"))
                            if (mapping.containsKey(child.asText()))
                                children.add(mapping.get(child.asText()));
                    }
                }
                parts.addPart(
                        mapping.get(message.path("id").asText()),
                        PartType.valueOf(part.path("type").asText()),
                        payload.toString());
            }
        for (var execution : doc.path("toolExecutions")) {
            var status = ToolStatus.valueOf(execution.path("status").asText());
            tools.saveExecution(
                    new ToolExecution(
                            mapping.get(execution.path("id").asText()),
                            id,
                            mapping.get(execution.path("messageId").asText()),
                            execution.path("toolCallId").asText(),
                            execution.path("name").asText(),
                            execution.path("parameters").toString(),
                            status == ToolStatus.PENDING || status == ToolStatus.RUNNING
                                    ? ToolStatus.INTERRUPTED
                                    : status,
                            execution.path("authorization").asText(),
                            nullable(execution, "result"),
                            execution.path("startedAt").asText(),
                            nullable(execution, "endedAt"),
                            nullable(execution, "error")));
        }
        if (doc.has("summaryThroughPosition"))
            service.summaries()
                    .save(
                            id,
                            doc.path("summaryThroughPosition").asInt(),
                            meta.path("summary").asText());
        if (doc.has("todos")) service.todos().write(id, doc.get("todos").toString());
        if (meta.has("task")) {
            var task = meta.get("task");
            String taskRoot =
                    mapping.getOrDefault(
                            nullable(task, "rootSessionId"), mapping.get(originalRoot));
            database.write(
                    c -> {
                        try (var s =
                                c.prepareStatement(
                                        "INSERT INTO agent_tasks(session_id,parent_session_id,root_session_id,call_id,parent_message_id,agent,scope_json,status,rounds,created_at,ended_at,diagnostic) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                            Object[] args = {
                                id,
                                parent,
                                taskRoot,
                                task.path("callId").asText(),
                                mapping.get(nullable(task, "parentMessageId")),
                                task.path("agent").asText(),
                                task.path("actors").toString(),
                                task.path("status").asText().equals("RUNNING")
                                        ? "INTERRUPTED"
                                        : task.path("status").asText(),
                                task.path("rounds").asInt(),
                                meta.path("createdAt").asText(),
                                meta.path("updatedAt").asText(),
                                nullable(task, "diagnostic")
                            };
                            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
                            s.executeUpdate();
                        }
                        try (var s =
                                c.prepareStatement(
                                        "INSERT INTO agent_task_budgets(root_session_id,tasks,rounds) VALUES(?,1,?) ON CONFLICT(root_session_id) DO UPDATE SET tasks=tasks+1,rounds=rounds+excluded.rounds")) {
                            s.setString(1, taskRoot);
                            s.setInt(2, task.path("rounds").asInt());
                            s.executeUpdate();
                        }
                        return null;
                    });
        }
        for (var row : doc.path("usage"))
            database.write(
                    c -> {
                        try (var s =
                                c.prepareStatement(
                                        "INSERT INTO llm_usage("
                                                + String.join(",", USAGE_COLUMNS)
                                                + ") VALUES("
                                                + String.join(
                                                        ",",
                                                        Collections.nCopies(
                                                                USAGE_COLUMNS.size(), "?"))
                                                + ")")) {
                            for (int i = 0; i < USAGE_COLUMNS.size(); i++) {
                                String column = USAGE_COLUMNS.get(i);
                                Object value;
                                if (column.equals("id") || column.equals("request_id"))
                                    value = mapping.get(row.path("request_id").asText());
                                else if (column.equals("session_id")) value = id;
                                else if (column.equals("status")
                                        && row.path(column).asText().equals("RUNNING"))
                                    value = "INTERRUPTED";
                                else value = JSON.convertValue(row.get(column), Object.class);
                                s.setObject(i + 1, value);
                            }
                            s.executeUpdate();
                        }
                        return null;
                    });
        for (var change : doc.path("fileChanges")) {
            String group = change.path("group").asText();
            mapping.computeIfAbsent(group, ignored -> UUID.randomUUID().toString());
            service.fileChanges()
                    .insert(
                            new FileChangeJournal.Change(
                                    mapping.get(change.path("id").asText()),
                                    mapping.get(group),
                                    id,
                                    change.path("call").asText(),
                                    change.path("path").asText(),
                                    blob(change, "before"),
                                    blob(change, "after"),
                                    "ABANDONED",
                                    change.path("created").asText()));
        }
    }

    private void timestamps(String table, String id, String created, String updated) {
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE "
                                            + table
                                            + " SET created_at=?,updated_at=? WHERE id=?")) {
                        s.setString(1, created);
                        s.setString(2, updated);
                        s.setString(3, id);
                        s.executeUpdate();
                    }
                    return null;
                });
    }

    private static ArrayNode array(JsonNode node, String field, int maximum) {
        var value = node.path(field);
        if (!value.isArray() || value.size() > maximum) throw invalid("Invalid array: " + field);
        return (ArrayNode) value;
    }

    private static String text(JsonNode node, String field, int maximum) {
        var value = node.path(field);
        if (!value.isTextual()
                || value.asText().isBlank()
                || value.asText().length() > maximum
                || value.asText().indexOf(0) >= 0) throw invalid("Invalid field: " + field);
        return value.asText();
    }

    private static String nullable(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw invalid("Invalid field: " + field);
        return value.asText();
    }

    private static String uuid(JsonNode node, String field) {
        String id = text(node, field, 36);
        if (!UUID.fromString(id).toString().equals(id)) throw invalid("Invalid UUID");
        return id;
    }

    private static void unique(Set<String> ids, String id) {
        if (!ids.add(id)) throw invalid("Duplicate archive identity");
    }

    private static int integer(JsonNode node, String field, int minimum, int maximum) {
        var value = node.path(field);
        if (!value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.asInt() < minimum
                || value.asInt() > maximum) throw invalid("Invalid integer: " + field);
        return value.asInt();
    }

    private static void timestamp(JsonNode node, String field) {
        Instant.parse(text(node, field, 64));
    }

    private static byte[] blob(JsonNode node, String field) {
        String value = nullable(node, field);
        if (value == null) return null;
        if (value.length() > 1333340) throw invalid("File snapshot exceeds 1 MB");
        var bytes = Base64.getDecoder().decode(value);
        if (bytes.length > 1000000) throw invalid("File snapshot exceeds 1 MB");
        return bytes;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid session archive: " + message);
    }
}
