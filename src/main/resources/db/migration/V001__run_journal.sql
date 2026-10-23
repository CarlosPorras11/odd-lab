CREATE TABLE journal_metadata (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    repository_id TEXT NOT NULL,
    migration_sha256 TEXT NOT NULL
);
CREATE TABLE runs (
    run_id TEXT PRIMARY KEY,
    repository_id TEXT NOT NULL,
    actor TEXT NOT NULL,
    request TEXT NOT NULL,
    request_sha256 TEXT NOT NULL,
    base_revision TEXT NOT NULL,
    policy_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('CREATED','EXPLORING','READY','RUNNING','VERIFYING','COMPLETED','WAITING_INPUT','PAUSING','PAUSED','CANCELLING','CANCELLED','RECONCILIATION_REQUIRED','FAILED')),
    version INTEGER NOT NULL CHECK (version >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX runs_by_repository ON runs(repository_id, created_at DESC, run_id);
CREATE TABLE run_entries (
    run_id TEXT NOT NULL REFERENCES runs(run_id),
    entry_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('REQUIREMENT','TASK','DECISION','EVIDENCE')),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    title TEXT NOT NULL,
    data_json TEXT NOT NULL,
    PRIMARY KEY (run_id, entry_id)
);
CREATE TABLE run_events (
    run_id TEXT NOT NULL REFERENCES runs(run_id),
    event_id TEXT NOT NULL UNIQUE,
    version INTEGER NOT NULL CHECK (version >= 0),
    type TEXT NOT NULL CHECK (type IN ('CREATED','STATE_CHANGED','ENTRY_ADDED')),
    previous_state TEXT,
    state TEXT NOT NULL,
    actor TEXT NOT NULL,
    detail TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    PRIMARY KEY (run_id, version)
);
