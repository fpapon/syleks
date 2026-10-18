CREATE TABLE agent_tasks (
 session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
 parent_session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL,
 root_session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL,
 call_id TEXT NOT NULL, parent_message_id TEXT REFERENCES messages(id) ON DELETE SET NULL,
 agent TEXT NOT NULL, scope_json TEXT NOT NULL CHECK(json_valid(scope_json)),
 status TEXT NOT NULL CHECK(status IN ('RUNNING','COMPLETED','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED')),
 rounds INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, ended_at TEXT, diagnostic TEXT
);
CREATE INDEX agent_tasks_parent ON agent_tasks(parent_session_id);
CREATE INDEX agent_tasks_root ON agent_tasks(root_session_id);
CREATE TABLE agent_task_budgets (
 root_session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
 tasks INTEGER NOT NULL DEFAULT 0, rounds INTEGER NOT NULL DEFAULT 0
);
