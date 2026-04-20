package com.activepulse.agent.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;

public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static final Path DB_DIR  = Paths.get(
            System.getProperty("user.home"), ".activepulse", "data");
    private static final Path DB_FILE = DB_DIR.resolve("activepulse.db");

    private static final int SCHEMA_VERSION = 4;

    private static volatile DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {}

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) instance = new DatabaseManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    public void init() {
        try {
            ensureDataDirectory();
            openConnection();
            configureSQLite();
            runMigrations();
            log.info("DatabaseManager initialised — {}", DB_FILE.toAbsolutePath());
        } catch (Exception e) {
            log.error("Database init failed", e);
            throw new RuntimeException("Database init failed", e);
        }
    }

    public Connection getConnection() {
        if (connection == null)
            throw new IllegalStateException("DatabaseManager not initialised.");
        return connection;
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.info("DB connection closed.");
            } catch (SQLException e) {
                log.warn("DB close error: {}", e.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Setup
    // ─────────────────────────────────────────────────────────────────

    private void ensureDataDirectory() throws IOException {
        if (!Files.exists(DB_DIR)) {
            Files.createDirectories(DB_DIR);
            log.info("Created data directory: {}", DB_DIR);
        }
    }

    private void openConnection() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:sqlite:" + DB_FILE.toAbsolutePath());
    }

    private void configureSQLite() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous  = NORMAL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA cache_size   = -8192");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Migrations
    // ─────────────────────────────────────────────────────────────────

    private void runMigrations() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version    INTEGER NOT NULL,
                    applied_at TEXT    NOT NULL
                )
            """);
        }
        int current = getCurrentVersion();
        log.info("DB schema — current: v{}, target: v{}", current, SCHEMA_VERSION);

        if (current < 1) applyMigration(1, this::migration_v1);
        if (current < 2) applyMigration(2, this::migration_v2_formattedTime);
        if (current < 3) applyMigration(3, this::migration_v3_activityType);
        if (current < 4) applyMigration(4, this::migration_v4_systemMetrics);

        log.info("DB schema is up to date.");
    }

    private int getCurrentVersion() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @FunctionalInterface
    private interface Migration { void run() throws SQLException; }

    private void applyMigration(int v, Migration m) throws SQLException {
        log.info("Applying migration v{}...", v);
        connection.setAutoCommit(false);
        try {
            m.run();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO schema_version (version, applied_at) VALUES (?, datetime('now'))")) {
                ps.setInt(1, v);
                ps.executeUpdate();
            }
            connection.commit();
            log.info("Migration v{} applied.", v);
        } catch (SQLException e) {
            connection.rollback();
            log.error("Migration v{} failed — rolled back.", v, e);
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  v1 — Full base schema
    // ─────────────────────────────────────────────────────────────────

    private void migration_v1() throws SQLException {
        try (Statement st = connection.createStatement()) {

            st.execute("""
                CREATE TABLE IF NOT EXISTS agent_config (
                    key        TEXT PRIMARY KEY,
                    value      TEXT NOT NULL,
                    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS app_config (
                    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                    username              TEXT    NOT NULL,
                    deviceid              TEXT    NOT NULL,
                    logintime             TEXT    NOT NULL,
                    logouttime            TEXT,
                    totaltime             INTEGER NOT NULL DEFAULT 0,
                    activetime            INTEGER NOT NULL DEFAULT 0,
                    idletime              INTEGER NOT NULL DEFAULT 0,
                    awaytime              INTEGER NOT NULL DEFAULT 0,
                    totalkeyboardcount    INTEGER NOT NULL DEFAULT 0,
                    totalkeymousecount    INTEGER NOT NULL DEFAULT 0,
                    status                TEXT    NOT NULL DEFAULT 'running',
                    created_at            TEXT    NOT NULL DEFAULT (datetime('now'))
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_app_config_user   ON app_config (username)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_app_config_device ON app_config (deviceid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_app_config_login  ON app_config (logintime)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS activity_log (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    username    TEXT    NOT NULL,
                    deviceid    TEXT    NOT NULL,
                    starttime   TEXT    NOT NULL,
                    endtime     TEXT,
                    processname TEXT    NOT NULL,
                    title       TEXT,
                    url         TEXT,
                    duration    INTEGER NOT NULL DEFAULT 0,
                    synced      INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_activity_user    ON activity_log (username)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_activity_device  ON activity_log (deviceid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_activity_start   ON activity_log (starttime)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_activity_synced  ON activity_log (synced)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_activity_process ON activity_log (processname)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS keyboard_mouse_strokes (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    username       TEXT    NOT NULL,
                    deviceid       TEXT    NOT NULL,
                    recorded_at    TEXT    NOT NULL,
                    keyboardcount  INTEGER NOT NULL DEFAULT 0,
                    keymousecount  INTEGER NOT NULL DEFAULT 0,
                    synced         INTEGER NOT NULL DEFAULT 0,
                    created_at     TEXT    NOT NULL DEFAULT (datetime('now'))
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_kms_user   ON keyboard_mouse_strokes (username)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_kms_device ON keyboard_mouse_strokes (deviceid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_kms_time   ON keyboard_mouse_strokes (recorded_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_kms_synced ON keyboard_mouse_strokes (synced)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS screenshots (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_name       TEXT    NOT NULL,
                    file_path       TEXT    NOT NULL,
                    file_size_bytes INTEGER,
                    captured_at     TEXT    NOT NULL,
                    synced          INTEGER NOT NULL DEFAULT 0,
                    created_at      TEXT    NOT NULL DEFAULT (datetime('now'))
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_screenshots_time   ON screenshots (captured_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_screenshots_synced ON screenshots (synced)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS sync_log (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    sync_id       TEXT    NOT NULL UNIQUE,
                    sync_start    TEXT    NOT NULL,
                    sync_end      TEXT    NOT NULL,
                    status        TEXT    NOT NULL DEFAULT 'pending',
                    response_code INTEGER,
                    error_message TEXT,
                    created_at    TEXT    NOT NULL DEFAULT (datetime('now'))
                )
            """);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  v2 — Formatted time columns on app_config
    // ─────────────────────────────────────────────────────────────────

    private void migration_v2_formattedTime() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE app_config ADD COLUMN totaltime_fmt  TEXT DEFAULT '0h 0m 0s'");
            st.execute("ALTER TABLE app_config ADD COLUMN activetime_fmt TEXT DEFAULT '0h 0m 0s'");
            st.execute("ALTER TABLE app_config ADD COLUMN idletime_fmt   TEXT DEFAULT '0h 0m 0s'");
            st.execute("ALTER TABLE app_config ADD COLUMN awaytime_fmt   TEXT DEFAULT '0h 0m 0s'");
            log.info("Migration v2: formatted time columns added.");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  v3 — activity_type column on activity_log
    // ─────────────────────────────────────────────────────────────────

    private void migration_v3_activityType() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE activity_log ADD COLUMN activity_type TEXT DEFAULT 'ACTIVE'");
            log.info("Migration v3: activity_type column added to activity_log.");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  v4 — Restore system_metrics table
    // ─────────────────────────────────────────────────────────────────

    private void migration_v4_systemMetrics() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS system_metrics (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    recorded_at      TEXT    NOT NULL,
                    cpu_usage_avg    REAL    NOT NULL DEFAULT 0.0,
                    memory_usage_avg REAL    NOT NULL DEFAULT 0.0,
                    network_status   TEXT    NOT NULL DEFAULT 'online',
                    synced           INTEGER NOT NULL DEFAULT 0,
                    created_at       TEXT    NOT NULL DEFAULT (datetime('now'))
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_sys_metrics_time   ON system_metrics (recorded_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sys_metrics_synced ON system_metrics (synced)");
            log.info("Migration v4: system_metrics table restored.");
        }
    }

} // ← class ends here