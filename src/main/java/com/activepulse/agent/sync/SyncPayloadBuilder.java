package com.activepulse.agent.sync;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.sync.payload.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SyncPayloadBuilder — reads all unsynced rows from the DB and
 * assembles the exact JSON payload the server expects.
 *
 * "Unsynced" = rows where synced = 0.
 * After a successful build the caller is responsible for marking
 * those rows as synced via markAsSynced().
 */
public class SyncPayloadBuilder {

    private static final Logger log = LoggerFactory.getLogger(SyncPayloadBuilder.class);

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile SyncPayloadBuilder instance;
    private SyncPayloadBuilder() {}

    public static SyncPayloadBuilder getInstance() {
        if (instance == null) {
            synchronized (SyncPayloadBuilder.class) {
                if (instance == null) instance = new SyncPayloadBuilder();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Build
    // ─────────────────────────────────────────────────────────────────

    /**
     * Reads all unsynced rows and assembles a SyncPayload.
     * Returns null if there is nothing to sync.
     */
    public SyncPayload build() {
        Connection conn = DatabaseManager.getInstance().getConnection();

        String syncStart = Instant.now().toString();

        List<ActivityLogEntry>  activityLogs = readActivityLogs(conn);
        List<AppEntry>          applications = readAppActivity(conn);
        List<WebsiteEntry>      websites     = readWebsites(conn);
        List<ScreenshotEntry>   screenshots  = readScreenshots(conn);
        SystemMetricsEntry      metrics      = readLatestSystemMetrics(conn);

        boolean hasData = !activityLogs.isEmpty()
                || !applications.isEmpty()
                || !websites.isEmpty()
                || !screenshots.isEmpty()
                || metrics != null;

        if (!hasData) {
            log.info("SyncPayloadBuilder — nothing to sync.");
            return null;
        }

        String syncEnd = Instant.now().toString();
        String syncId  = "SYNC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        SyncPayload payload = SyncPayload.builder()
                .syncId(syncId)
                .deviceId(getConfig(conn, "deviceId",     "DEV-UNKNOWN"))
                .userId(getConfig(conn, "userId",          "USR-UNKNOWN"))
                .organizationId(getConfig(conn, "orgId",   "ORG-UNKNOWN"))
                .agentVersion(getConfig(conn, "agentVersion", "1.0.0"))
                .osType(normalizeOsType(getConfig(conn, "osName", "Unknown")))
                .syncStartTime(syncStart)
                .syncEndTime(syncEnd)
                .activityLogs(activityLogs)
                .applications(applications)
                .websites(websites)
                .screenshots(screenshots)
                .systemMetrics(metrics)
                .build();

        log.info("SyncPayload built — syncId={} activityLogs={} apps={} websites={} screenshots={} metrics={}",
                syncId,
                activityLogs.size(),
                applications.size(),
                websites.size(),
                screenshots.size(),
                metrics != null ? "yes" : "none");

        return payload;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Mark synced — call after confirmed server delivery
    // ─────────────────────────────────────────────────────────────────

    public void markAsSynced(SyncPayload payload) {
        if (payload == null) return;
        Connection conn = DatabaseManager.getInstance().getConnection();

        markSynced(conn, "activity_logs",  payload.getActivityLogs()
                .stream().map(ActivityLogEntry::getDbId).toList());
        markSynced(conn, "app_activity",   payload.getApplications()
                .stream().map(AppEntry::getDbId).toList());
        markSynced(conn, "websites",       payload.getWebsites()
                .stream().map(WebsiteEntry::getDbId).toList());
        markSynced(conn, "screenshots",    payload.getScreenshots()
                .stream().map(ScreenshotEntry::getDbId).toList());

        if (payload.getSystemMetrics() != null) {
            markSynced(conn, "system_metrics",
                    List.of(payload.getSystemMetrics().getDbId()));
        }

        log.info("Marked rows as synced for syncId={}", payload.getSyncId());
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB readers
    // ─────────────────────────────────────────────────────────────────

    private List<ActivityLogEntry> readActivityLogs(Connection conn) {
        List<ActivityLogEntry> list = new ArrayList<>();
        String sql = """
            SELECT id, start_time, end_time, active_seconds, idle_seconds,
                   keyboard_clicks, mouse_clicks, mouse_movement
            FROM activity_logs WHERE synced = 0 ORDER BY start_time
            """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new ActivityLogEntry(
                        rs.getLong("id"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getInt("active_seconds"),
                        rs.getInt("idle_seconds"),
                        rs.getInt("keyboard_clicks"),
                        rs.getInt("mouse_clicks"),
                        rs.getDouble("mouse_movement")
                ));
            }
        } catch (SQLException e) {
            log.error("Failed to read activity_logs: {}", e.getMessage());
        }
        return list;
    }

    private List<AppEntry> readAppActivity(Connection conn) {
        List<AppEntry> list = new ArrayList<>();
        String sql = """
            SELECT id, app_name, window_title, start_time, end_time, duration_seconds
            FROM app_activity WHERE synced = 0 ORDER BY start_time
            """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new AppEntry(
                        rs.getLong("id"),
                        rs.getString("app_name"),
                        rs.getString("window_title"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getInt("duration_seconds")
                ));
            }
        } catch (SQLException e) {
            log.error("Failed to read app_activity: {}", e.getMessage());
        }
        return list;
    }

    private List<WebsiteEntry> readWebsites(Connection conn) {
        List<WebsiteEntry> list = new ArrayList<>();
        String sql = """
            SELECT id, url, domain, start_time, end_time, duration_seconds
            FROM websites WHERE synced = 0 ORDER BY start_time
            """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new WebsiteEntry(
                        rs.getLong("id"),
                        rs.getString("url"),
                        rs.getString("domain"),
                        rs.getString("start_time"),
                        rs.getString("end_time"),
                        rs.getInt("duration_seconds")
                ));
            }
        } catch (SQLException e) {
            log.error("Failed to read websites: {}", e.getMessage());
        }
        return list;
    }

    private List<ScreenshotEntry> readScreenshots(Connection conn) {
        List<ScreenshotEntry> list = new ArrayList<>();
        String sql = """
            SELECT id, file_name, captured_at
            FROM screenshots WHERE synced = 0 ORDER BY captured_at
            """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new ScreenshotEntry(
                        rs.getLong("id"),
                        rs.getString("file_name"),
                        rs.getString("captured_at")
                ));
            }
        } catch (SQLException e) {
            log.error("Failed to read screenshots: {}", e.getMessage());
        }
        return list;
    }

    /**
     * systemMetrics in the payload is a single aggregated object —
     * we average all unsynced rows into one entry.
     */
    private SystemMetricsEntry readLatestSystemMetrics(Connection conn) {
        String sql = """
            SELECT id, cpu_usage_avg, memory_usage_avg, network_status
            FROM system_metrics WHERE synced = 0
            ORDER BY recorded_at DESC LIMIT 1
            """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return new SystemMetricsEntry(
                        rs.getLong("id"),
                        rs.getDouble("cpu_usage_avg"),
                        rs.getDouble("memory_usage_avg"),
                        rs.getString("network_status")
                );
            }
        } catch (SQLException e) {
            log.error("Failed to read system_metrics: {}", e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private void markSynced(Connection conn, String table, List<Long> ids) {
        if (ids.isEmpty()) return;
        String placeholders = "?,".repeat(ids.size());
        placeholders = placeholders.substring(0, placeholders.length() - 1);
        String sql = "UPDATE " + table + " SET synced = 1 WHERE id IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            int updated = ps.executeUpdate();
            log.debug("Marked {} rows synced in {}", updated, table);
        } catch (SQLException e) {
            log.error("Failed to mark synced in {}: {}", table, e.getMessage());
        }
    }

    private String getConfig(Connection conn, String key, String fallback) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM agent_config WHERE key = ?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (SQLException e) {
            log.debug("Config key '{}' not found: {}", key, e.getMessage());
        }
        return fallback;
    }

    /** Normalize OS name to clean type string for payload. */
    private String normalizeOsType(String osName) {
        if (osName == null) return "Unknown";
        String lower = osName.toLowerCase();
        if (lower.contains("win"))   return "Windows";
        if (lower.contains("mac"))   return "macOS";
        if (lower.contains("linux")) return "Linux";
        return osName;
    }
}
