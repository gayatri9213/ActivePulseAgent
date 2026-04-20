-- ActivePulse Agent SQLite schema
-- Executed by DatabaseManager on first run and on version upgrades.

CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);

-- Activity log: one row per window focus session
CREATE TABLE IF NOT EXISTS activity_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL,
    deviceid      TEXT NOT NULL,
    starttime     TEXT NOT NULL,   -- IST, yyyy-MM-dd HH:mm:ss
    endtime       TEXT NOT NULL,
    processname   TEXT,
    title         TEXT,
    url           TEXT,
    duration      INTEGER NOT NULL DEFAULT 0,  -- seconds
    activity_type TEXT NOT NULL DEFAULT 'active',
    recorded_at   TEXT NOT NULL,
    synced        INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_activity_synced   ON activity_log(synced);
CREATE INDEX IF NOT EXISTS idx_activity_recorded ON activity_log(recorded_at);

-- Keyboard/mouse stroke aggregates (per minute)
CREATE TABLE IF NOT EXISTS keyboard_mouse_strokes (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    username       TEXT NOT NULL,
    deviceid       TEXT NOT NULL,
    recorded_at    TEXT NOT NULL,
    keyboardcount  INTEGER NOT NULL DEFAULT 0,
    keymousecount  INTEGER NOT NULL DEFAULT 0,
    synced         INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_strokes_synced   ON keyboard_mouse_strokes(synced);
CREATE INDEX IF NOT EXISTS idx_strokes_recorded ON keyboard_mouse_strokes(recorded_at);

-- Screenshots
CREATE TABLE IF NOT EXISTS screenshots (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    username    TEXT NOT NULL,
    deviceid    TEXT NOT NULL,
    file_path   TEXT NOT NULL,
    captured_at TEXT NOT NULL,
    file_size   INTEGER,
    synced      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_screenshots_synced   ON screenshots(synced);
CREATE INDEX IF NOT EXISTS idx_screenshots_captured ON screenshots(captured_at);

-- Agent runtime config (key/value)
CREATE TABLE IF NOT EXISTS agent_config (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Sync cycle history
CREATE TABLE IF NOT EXISTS sync_log (
    sync_id       TEXT PRIMARY KEY,
    sync_start    TEXT NOT NULL,
    sync_end      TEXT NOT NULL,
    status        TEXT NOT NULL,
    response_code INTEGER NOT NULL DEFAULT 0,
    message       TEXT
);

CREATE INDEX IF NOT EXISTS idx_synclog_status ON sync_log(status);
