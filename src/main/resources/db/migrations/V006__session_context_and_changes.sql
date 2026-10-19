CREATE TABLE IF NOT EXISTS session_summaries (
 session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
 through_position INTEGER NOT NULL CHECK(through_position >= 0),
 content TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS file_changes (
 id TEXT PRIMARY KEY, group_id TEXT NOT NULL,
 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
 tool_call_id TEXT NOT NULL, path TEXT NOT NULL,
 before_content BLOB, after_content BLOB,
 state TEXT NOT NULL CHECK(state IN ('PENDING','APPLIED','UNDONE','NO_EFFECT','CONFLICT','ABANDONED','PENDING_UNDO','PENDING_REDO')),
 created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS file_changes_session ON file_changes(session_id,created_at,id);
