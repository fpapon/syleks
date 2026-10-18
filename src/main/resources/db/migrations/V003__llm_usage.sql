CREATE TABLE llm_usage (
 id TEXT PRIMARY KEY, request_id TEXT NOT NULL UNIQUE,
 session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
 provider_id TEXT NOT NULL, model_id TEXT NOT NULL,
 input_tokens INTEGER, output_tokens INTEGER, total_tokens INTEGER,
 cached_input_tokens INTEGER, reasoning_output_tokens INTEGER,
 duration_ms INTEGER, first_token_latency_ms INTEGER, tokens_per_second REAL,
 provider_duration_ns INTEGER, prompt_duration_ns INTEGER, generation_duration_ns INTEGER,
 context_window INTEGER, usage_source TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('RUNNING','COMPLETED','FAILED','INTERRUPTED')),
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX idx_llm_usage_session ON llm_usage(session_id, created_at);
