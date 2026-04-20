package com.activepulse.agent.monitor;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SessionReportManager — one row per agent run in session_report table.
 *
 * All timestamps stored in IST.
 * Duration columns stored as both raw seconds AND formatted "Xh Xm Xs".
 */
public class SessionReportManager {

    private static final Logger log = LoggerFactory.getLogger(SessionReportManager.class);

    private final AtomicInteger totalActive  = new AtomicInteger(0);
    private final AtomicInteger totalNeutral = new AtomicInteger(0);
    private final AtomicInteger totalIdle    = new AtomicInteger(0);
    private final AtomicInteger totalKeys    = new AtomicInteger(0);
    private final AtomicInteger totalClicks  = new AtomicInteger(0);
    private final AtomicLong    totalDistBits = new AtomicLong(
            Double.doubleToLongBits(0.0));

    private String sessionId;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile SessionReportManager instance;
    private SessionReportManager() {}

    public static SessionReportManager getInstance() {
        if (instance == null) {
            synchronized (SessionReportManager.class) {
                if (instance == null) instance = new SessionReportManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        sessionId = "SES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String loginTime = TimeUtil.nowIST();           // ← IST
        String deviceId  = readConfig("deviceId", "DEV-UNKNOWN");
        String userName  = System.getProperty("user.name");

        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO session_report
                        (session_id, device_id, user_name, login_time, status)
                    VALUES (?, ?, ?, ?, 'running')
                    """)) {
                ps.setString(1, sessionId);
                ps.setString(2, deviceId);
                ps.setString(3, userName);
                ps.setString(4, loginTime);
                ps.executeUpdate();
            }
            log.info("Session started → sessionId={} loginTime={} (IST)", sessionId, loginTime);
        } catch (SQLException e) {
            log.error("Failed to record session start: {}", e.getMessage());
        }
    }

    public void accumulate(int activeSecs, int neutralSecs, int idleSecs,
                           int keys, int clicks, double distance) {
        totalActive.addAndGet(activeSecs);
        totalNeutral.addAndGet(neutralSecs);
        totalIdle.addAndGet(idleSecs);
        totalKeys.addAndGet(keys);
        totalClicks.addAndGet(clicks);

        long expected, updated;
        do {
            expected = totalDistBits.get();
            updated  = Double.doubleToLongBits(Double.longBitsToDouble(expected) + distance);
        } while (!totalDistBits.compareAndSet(expected, updated));

        updateSessionRow("running", null);
    }

    public void stop() {
        String logoutTime = TimeUtil.nowIST();          // ← IST
        updateSessionRow("stopped", logoutTime);
        printSummary();
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB update
    // ─────────────────────────────────────────────────────────────────

    private void updateSessionRow(String status, String logoutTime) {
        if (sessionId == null) return;

        int    activeSecs  = totalActive.get();
        int    neutralSecs = totalNeutral.get();
        int    idleSecs    = totalIdle.get();
        long   totalSecs   = activeSecs + neutralSecs + idleSecs;
        double dist        = Double.longBitsToDouble(totalDistBits.get());

        // Formatted durations e.g. "1h 23m 45s"
        String activeFmt  = TimeUtil.formatDuration(activeSecs);
        String neutralFmt = TimeUtil.formatDuration(neutralSecs);
        String idleFmt    = TimeUtil.formatDuration(idleSecs);
        String totalFmt   = TimeUtil.formatDuration(totalSecs);

        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE session_report SET
                        logout_time             = COALESCE(?, logout_time),
                        total_active_seconds    = ?,
                        total_neutral_seconds   = ?,
                        total_idle_seconds      = ?,
                        total_keyboard_clicks   = ?,
                        total_mouse_clicks      = ?,
                        total_mouse_distance    = ?,
                        active_time_formatted   = ?,
                        neutral_time_formatted  = ?,
                        idle_time_formatted     = ?,
                        total_time_formatted    = ?,
                        status                  = ?
                    WHERE session_id = ?
                    """)) {
                ps.setString(1,  logoutTime);
                ps.setInt(2,     activeSecs);
                ps.setInt(3,     neutralSecs);
                ps.setInt(4,     idleSecs);
                ps.setInt(5,     totalKeys.get());
                ps.setInt(6,     totalClicks.get());
                ps.setDouble(7,  dist);
                ps.setString(8,  activeFmt);
                ps.setString(9,  neutralFmt);
                ps.setString(10, idleFmt);
                ps.setString(11, totalFmt);
                ps.setString(12, status);
                ps.setString(13, sessionId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to update session row: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Summary on shutdown
    // ─────────────────────────────────────────────────────────────────

    private void printSummary() {
        long totalSecs = totalActive.get() + totalNeutral.get() + totalIdle.get();
        double dist    = Double.longBitsToDouble(totalDistBits.get());

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("  Session Report — {}", sessionId);
        log.info("  Total session : {}", TimeUtil.formatDuration(totalSecs));
        log.info("  Working       : {} ({} s)", TimeUtil.formatDuration(totalActive.get()),  totalActive.get());
        log.info("  Neutral       : {} ({} s)", TimeUtil.formatDuration(totalNeutral.get()), totalNeutral.get());
        log.info("  Idle          : {} ({} s)", TimeUtil.formatDuration(totalIdle.get()),    totalIdle.get());
        log.info("  Keystrokes    : {}", totalKeys.get());
        log.info("  Mouse clicks  : {}", totalClicks.get());
        log.info("  Mouse moved   : {} px", String.format("%.0f", dist));
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private String readConfig(String key, String fallback) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM agent_config WHERE key = ?")) {
                ps.setString(1, key);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException ignored) {}
        return fallback;
    }
}