CREATE TABLE session_todos (
    session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    todos_json TEXT NOT NULL
);
