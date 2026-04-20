package com.activepulse.agent.sync;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.util.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SyncConfig — seeds agent_config table from EnvConfig (agent.env).
 *
 * Values written to agent_config on every startup:
 *   serverUrl      ← SERVER_BASE_URL
 *   apiKey         ← API_KEY
 *   userId         ← USER_ID
 *   orgId          ← ORGANIZATION_ID
 *   agentVersion   ← AGENT_VERSION
 *
 * isConfigured() returns true only when SERVER_BASE_URL and API_KEY
 * are real values (not placeholders).
 */
public class SyncConfig {

    private static final Logger log = LoggerFactory.getLogger(SyncConfig.class);

    private static volatile SyncConfig instance;
    private SyncConfig() {}

    public static SyncConfig getInstance() {
        if (instance == null) {
            synchronized (SyncConfig.class) {
                if (instance == null) instance = new SyncConfig();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Seed — called once at startup from ActivePulseApplication
    //  Writes EnvConfig values into agent_config table.
    //  Uses INSERT OR REPLACE so values are always kept current.
    // ─────────────────────────────────────────────────────────────────

    public void seed() {
        String serverUrl    = EnvConfig.get("SERVER_BASE_URL", "https://api.activepulse.com");
        String apiKey       = EnvConfig.get("API_KEY",          "YOUR_API_KEY_HERE");
        String userId       = EnvConfig.get("USER_ID",          "0");
        String orgId        = EnvConfig.get("ORGANIZATION_ID",  "0");
        String agentVersion = EnvConfig.get("AGENT_VERSION",    "1.0.0");

        upsert("serverUrl",    serverUrl);
        upsert("apiKey",       apiKey);
        upsert("userId",       userId);
        upsert("orgId",        orgId);
        upsert("agentVersion", agentVersion);

        if (isConfigured()) {
            log.info("SyncConfig seeded — server: {}", serverUrl);
            log.info("  userId: {}  orgId: {}", userId, orgId);
        } else {
            log.warn("SyncConfig seeded — API key not configured.");
            log.warn("  Set SERVER_BASE_URL and API_KEY in agent.env to enable HTTP sync.");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API — always reads live from agent_config
    // ─────────────────────────────────────────────────────────────────

    public String getServerUrl()  { return read("serverUrl",    ""); }
    public String getApiKey()     { return read("apiKey",       ""); }
    public String getUserId()     { return read("userId",       "0"); }
    public String getOrgId()      { return read("orgId",        "0"); }
    public String getAgentVersion(){ return read("agentVersion","1.0.0"); }

    /**
     * Returns true only when both SERVER_BASE_URL and API_KEY
     * are set to real values (not placeholders).
     */
    public boolean isConfigured() {
        return EnvConfig.isSet("SERVER_BASE_URL")
                && EnvConfig.isSet("API_KEY");
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB helpers
    // ─────────────────────────────────────────────────────────────────

    private void upsert(String key, String value) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO agent_config (key, value) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("SyncConfig upsert '{}' failed: {}", key, e.getMessage());
        }
    }

    private String read(String key, String fallback) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM agent_config WHERE key = ?")) {
                ps.setString(1, key);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            log.debug("SyncConfig read '{}' failed: {}", key, e.getMessage());
        }
        return fallback;
    }
}