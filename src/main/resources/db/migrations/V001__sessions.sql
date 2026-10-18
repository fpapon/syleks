CREATE TABLE projects (
 id TEXT PRIMARY KEY, root_path TEXT NOT NULL UNIQUE, git_remote TEXT, name TEXT NOT NULL,
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE sessions (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
 parent_session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL, title TEXT NOT NULL,
 provider TEXT NOT NULL, model TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('ACTIVE','IDLE','INTERRUPTED','ARCHIVED')),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL, summary TEXT
);
CREATE INDEX sessions_project_activity ON sessions(project_id, updated_at DESC);
CREATE TABLE messages (
 id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
 role TEXT NOT NULL CHECK(role IN ('system','user','assistant','tool')),
 status TEXT NOT NULL CHECK(status IN ('COMPLETE','GENERATING','INTERRUPTED','ERROR')),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL, position INTEGER NOT NULL,
 UNIQUE(session_id, position)
);
CREATE TABLE message_parts (
 id TEXT PRIMARY KEY, message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
 position INTEGER NOT NULL, type TEXT NOT NULL CHECK(type IN ('TEXT','REASONING','TOOL_CALL','TOOL_RESULT','CODE','FILE_REFERENCE','DIFF')),
 payload_json TEXT NOT NULL CHECK(json_valid(payload_json)), schema_version INTEGER NOT NULL DEFAULT 1,
 UNIQUE(message_id, position)
);
CREATE TABLE tool_executions (
 id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
 message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE, tool_call_id TEXT NOT NULL,
 name TEXT NOT NULL, parameters_json TEXT NOT NULL CHECK(json_valid(parameters_json)),
 status TEXT NOT NULL CHECK(status IN ('PENDING','RUNNING','SUCCESS','FAILED','CANCELLED','INTERRUPTED')),
 authorization TEXT NOT NULL, result TEXT, started_at TEXT NOT NULL, ended_at TEXT, error TEXT
);
CREATE INDEX tool_executions_session ON tool_executions(session_id);
