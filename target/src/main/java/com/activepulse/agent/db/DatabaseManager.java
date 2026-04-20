package com.activepulse.agent.db;

import com.activepulse.agent.util.PathResolver;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;

/**
 * SQLite connection manager. One connection, reused across the agent.
 * SQLite handles single-writer concurrency fine for our workload (~10 writes/sec peak).
 */
public final class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static final int SCHEMA_VERSION = 1;

    private static volatile DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {
        init();
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) instance = new DatabaseManager();
            }
        }
        return instance;
    }

    private void init() {
        Path dbFile = PathResolver.databaseFile();
        String url = "jdbc:sqlite:" + dbFile.toString().replace("\\", "/");
        log.info("Opening SQLite DB at {}", dbFile);

        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(url);
            connection.setAutoCommit(true);

            // Performance pragmas
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA synchronous = NORMAL");
                st.execute("PRAGMA busy_timeout = 5000");
                st.execute("PRAGMA foreign_keys = ON");
            }

            applySchema();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init SQLite: " + e.getMessage(), e);
        }
    }

    private void applySchema() throws SQLException {
        String sql = loadSchemaSql();
        try (Statement st = connection.createStatement()) {
            for (String stmt : sql.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }

        // Record schema version if new
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) AS v FROM schema_version")) {
            int current = rs.next() ? rs.getInt("v") : 0;
            if (current < SCHEMA_VERSION) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO schema_version(version, applied_at) VALUES(?, ?)")) {
                    ps.setInt(1, SCHEMA_VERSION);
                    ps.setString(2, TimeUtil.nowIST());
                    ps.executeUpdate();
                }
                log.info("Applied schema version {}", SCHEMA_VERSION);
            }
        }
    }

    private String loadSchemaSql() {
        // Try classpath first (packaged JAR), then filesystem (dev run)
        try (InputStream in = getClass().getResourceAsStream("/sql/schema.sql")) {
            if (in != null) return readAll(in);
        } catch (Exception ignored) {}
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            if (in != null) return readAll(in);
        } catch (Exception ignored) {}
        throw new IllegalStateException("schema.sql not found on classpath");
    }

    private String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public Connection getConnection() { return connection; }

    public void setConfig(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO agent_config(key, value) VALUES(?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("setConfig({}) failed: {}", key, e.getMessage());
        }
    }

    public String getConfig(String key, String fallback) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM agent_config WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            log.warn("getConfig({}) failed: {}", key, e.getMessage());
        }
        return fallback;
    }

    public synchronized void shutdown() {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
            connection = null;
            log.info("Database connection closed.");
        }
    }
}
