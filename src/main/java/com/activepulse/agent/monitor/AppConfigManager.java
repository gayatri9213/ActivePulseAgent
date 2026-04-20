package com.activepulse.agent.monitor;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AppConfigManager — manages the app_config table.
 * One row per agent session (login → logout).
 *
 * Tracks:
 *   logintime / logouttime
 *   totaltime / activetime / idletime / awaytime
 *   totalkeyboardcount / totalkeymousecount
 */
public class AppConfigManager {

    private static final Logger log = LoggerFactory.getLogger(AppConfigManager.class);

    private final AtomicInteger activeTime  = new AtomicInteger(0);
    private final AtomicInteger idleTime    = new AtomicInteger(0);
    private final AtomicInteger awayTime    = new AtomicInteger(0);
    private final AtomicInteger keyCount    = new AtomicInteger(0);
    private final AtomicInteger mouseCount  = new AtomicInteger(0);
    private final AtomicLong    sessionId   = new AtomicLong(-1);

    private String username;
    private String deviceid;
    private String lastLoginDate;
    private LocalDateTime sessionStartDateTime;
    private boolean isContinuationSession = false;
    private String firstLoginTime; // Store the first login time of the day

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile AppConfigManager instance;
    private AppConfigManager() {}

    public static AppConfigManager getInstance() {
        if (instance == null) {
            synchronized (AppConfigManager.class) {
                if (instance == null) instance = new AppConfigManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        username = readConfig("userName",  System.getProperty("user.name"));
        deviceid = readConfig("deviceId",  "DEV-UNKNOWN");

        String logintime = TimeUtil.nowIST();
        String currentDate = extractDate(logintime);
        
        // Check if there's an existing session for today that can be continued
        if (checkForExistingTodaySession(currentDate)) {
            continueExistingSession(currentDate, logintime);
        } else {
            startNewSession(currentDate, logintime);
        }
    }

    /**
     * Called every 60s by InputActivityRecorder after flush.
     * Accumulates time + input counts and updates the live DB row.
     */
    public void accumulate(int activeSecs, int idleSecs, int awaySecs,
                           int keys, int mouse) {
        checkAndHandleDateChange();
        activeTime.addAndGet(activeSecs);
        idleTime.addAndGet(idleSecs);
        awayTime.addAndGet(awaySecs);
        keyCount.addAndGet(keys);
        mouseCount.addAndGet(mouse);
        updateRow(null);
    }

    public void checkAndHandleDateChange() {
        String currentDate = extractDate(TimeUtil.nowIST());
        if (lastLoginDate != null && !currentDate.equals(lastLoginDate)) {
            log.info("Date changed from {} to {}, ending previous session at day end", lastLoginDate, currentDate);
            closePreviousSessionAtDayEnd();
            
            // Sync data before starting new session for the new day
            try {
                com.activepulse.agent.sync.SyncManager.getInstance().syncEndOfDay();
            } catch (Exception e) {
                log.warn("Failed to sync data during date change: {}", e.getMessage());
            }
            
            startNewSession(currentDate);
        }
    }

    public void splitMultiDayAwaySession() {
        if (sessionId.get() <= 0) return;
        
        String currentTime = TimeUtil.nowIST();
        String currentDate = extractDate(currentTime);
        
        // If the current session spans multiple days, split it
        if (lastLoginDate != null && !currentDate.equals(lastLoginDate)) {
            log.info("Splitting multi-day away session from {} to {}", lastLoginDate, currentDate);
            
            // Close the previous day's session at 23:59:59
            closePreviousSessionAtDayEnd();
            
            // Start new sessions for each missing day
            LocalDate start = LocalDate.parse(lastLoginDate);
            LocalDate end = LocalDate.parse(currentDate);
            
            // Create sessions for intermediate days (all away sessions)
            for (LocalDate date = start.plusDays(1); date.isBefore(end); date = date.plusDays(1)) {
                createAwaySessionForDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
            }
            
            // Start current day session
            startNewSession(currentDate);
        }
    }
    
    private void createAwaySessionForDate(String date) {
        String dayStart = date + " 00:00:00";
        String dayEnd = date + " 23:59:59";
        int fullDaySeconds = 24 * 60 * 60;
        
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app_config 
                        (username, deviceid, logintime, logouttime, status,
                         totaltime, activetime, idletime, awaytime,
                         totalkeyboardcount, totalkeymousecount,
                         totaltime_fmt, activetime_fmt, idletime_fmt, awaytime_fmt)
                    VALUES (?, ?, ?, ?, 'stopped', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, username);
                ps.setString(2, deviceid);
                ps.setString(3, dayStart);
                ps.setString(4, dayEnd);
                ps.setInt(5, fullDaySeconds);
                ps.setInt(6, 0); // No active time
                ps.setInt(7, 0); // No idle time  
                ps.setInt(8, fullDaySeconds); // Full away time
                ps.setInt(9, 0); // No keyboard activity
                ps.setInt(10, 0); // No mouse activity
                ps.setString(11, TimeUtil.formatDuration(fullDaySeconds));
                ps.setString(12, "0h 0m 0s");
                ps.setString(13, "0h 0m 0s");
                ps.setString(14, TimeUtil.formatDuration(fullDaySeconds));
                ps.executeUpdate();
                
                log.info("Created away session for date {} → 24h away time", date);
            }
        } catch (SQLException e) {
            log.error("Failed to create away session for date {}: {}", date, e.getMessage());
        }
    }

    private void startNewSession(String newDate) {
        String logintime = TimeUtil.nowIST();
        startNewSession(newDate, logintime);
    }

    private void closePreviousSessionAtDayEnd() {
        if (sessionId.get() > 0) {
            // Set logout time to end of previous day (23:59:59)
            String dayEndLogoutTime = lastLoginDate + " 23:59:59";
            updateRow(dayEndLogoutTime, "stopped");
            log.info("Previous session closed at day end — id={} logouttime={}", sessionId.get(), dayEndLogoutTime);
        }
    }

    private boolean checkForExistingTodaySession(String currentDate) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, logintime, totaltime, activetime, idletime, awaytime, 
                           totalkeyboardcount, totalkeymousecount
                    FROM app_config 
                    WHERE username = ? AND deviceid = ? AND DATE(logintime) = ? 
                    AND status = 'stopped'
                    ORDER BY logintime DESC LIMIT 1
                    """)) {
                ps.setString(1, username);
                ps.setString(2, deviceid);
                ps.setString(3, currentDate);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    // Found existing session for today
                    long existingSessionId = rs.getLong("id");
                    // Store the first login time
                    firstLoginTime = rs.getString("logintime");
                    // Load existing session data
                    activeTime.set(rs.getInt("activetime"));
                    idleTime.set(rs.getInt("idletime"));
                    awayTime.set(rs.getInt("awaytime"));
                    keyCount.set(rs.getInt("totalkeyboardcount"));
                    mouseCount.set(rs.getInt("totalkeymousecount"));
                    
                    // Delete the old session record and continue with new one
                    try (PreparedStatement deletePs = conn.prepareStatement(
                            "DELETE FROM app_config WHERE id = ?")) {
                        deletePs.setLong(1, existingSessionId);
                        deletePs.executeUpdate();
                    }
                    
                    log.info("Found existing session for today (id={}), first login time preserved: {}", existingSessionId, firstLoginTime);
                    return true;
                }
            }
        } catch (SQLException e) {
            log.error("Error checking for existing today session: {}", e.getMessage());
        }
        return false;
    }

    private void continueExistingSession(String currentDate, String logintime) {
        lastLoginDate = currentDate;
        sessionStartDateTime = LocalDateTime.now();
        isContinuationSession = true;
        
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app_config (username, deviceid, logintime, status,
                        totaltime, activetime, idletime, awaytime,
                        totalkeyboardcount, totalkeymousecount)
                    VALUES (?, ?, ?, 'running', ?, ?, ?, ?, ?, ?)
                    """, PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, deviceid);
                // Use the first login time, not the current login time
                ps.setString(3, firstLoginTime);
                
                int total = activeTime.get() + idleTime.get() + awayTime.get();
                ps.setInt(4, total);
                ps.setInt(5, activeTime.get());
                ps.setInt(6, idleTime.get());
                ps.setInt(7, awayTime.get());
                ps.setInt(8, keyCount.get());
                ps.setInt(9, mouseCount.get());
                
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) sessionId.set(keys.getLong(1));
            }
            log.info("AppConfig session continued — id={} user={} device={} first_login={} current_login={} (accumulated: {}h {}m {}m)",
                    sessionId.get(), username, deviceid, firstLoginTime, logintime,
                    activeTime.get() / 3600, (activeTime.get() % 3600) / 60, awayTime.get() / 60);
        } catch (SQLException e) {
            log.error("Failed to continue app_config session: {}", e.getMessage());
        }
    }

    private void startNewSession(String currentDate, String logintime) {
        lastLoginDate = currentDate;
        sessionStartDateTime = LocalDateTime.now();
        isContinuationSession = false;
        firstLoginTime = logintime; // Store the first login time
        
        // Reset counters for fresh session
        activeTime.set(0);
        idleTime.set(0);
        awayTime.set(0);
        keyCount.set(0);
        mouseCount.set(0);
        
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO app_config (username, deviceid, logintime, status)
                    VALUES (?, ?, ?, 'running')
                    """, PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, deviceid);
                ps.setString(3, logintime);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) sessionId.set(keys.getLong(1));
            }
            log.info("AppConfig new session started — id={} user={} device={} first_login={}",
                    sessionId.get(), username, deviceid, logintime);
        } catch (SQLException e) {
            log.error("Failed to start new app_config session: {}", e.getMessage());
        }
    }

    private String extractDate(String timestamp) {
        try {
            // Extract date part from timestamp like "2026-04-02 19:24:49"
            return timestamp.split(" ")[0];
        } catch (Exception e) {
            return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
    }

    public void stop() {
        String logouttime = TimeUtil.nowIST();
        updateRow(logouttime);
        printSummary();
        log.info("AppConfig session ended — id={}", sessionId.get());
    }

    public void shutdown() {
        if (sessionId.get() > 0) {
            String logouttime = TimeUtil.nowIST();
            updateRow(logouttime, "stopped");
            printSummary();
            log.info("AppConfig session shutdown — id={} logouttime={}", sessionId.get(), logouttime);
        }
        
        // Sync data before shutdown to ensure data safety
        try {
            com.activepulse.agent.sync.SyncManager.getInstance().syncBeforeShutdown();
        } catch (Exception e) {
            log.warn("Failed to sync data before shutdown: {}", e.getMessage());
        }
    }

    public void setAwayStatus() {
        updateRow(null, "away");
        log.info("Session status updated to AWAY for session id={}", sessionId.get());
    }

    public void setActiveStatus() {
        updateRow(null, "running");
        log.info("Session status updated to RUNNING for session id={}", sessionId.get());
    }

    public void setSleepStatus() {
        updateRow(null, "away");
        log.info("Session status updated to SLEEP/AWAY for session id={}", sessionId.get());
    }

    private void updateRow(String logouttime) {
        updateRow(logouttime, logouttime != null ? "stopped" : "running");
    }

    private void updateRow(String logouttime, String status) {
        long id = sessionId.get();
        if (id < 0) return;

        int active = activeTime.get();
        int idle   = idleTime.get();
        int away   = awayTime.get();
        int total  = active + idle + away;

        // Raw seconds + formatted strings
        String totalFmt  = TimeUtil.formatDuration(total);
        String activeFmt = TimeUtil.formatDuration(active);
        String idleFmt   = TimeUtil.formatDuration(idle);
        String awayFmt   = TimeUtil.formatDuration(away);

        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE app_config SET
                        logouttime         = COALESCE(?, logouttime),
                        totaltime          = ?,
                        activetime         = ?,
                        idletime           = ?,
                        awaytime           = ?,
                        totalkeyboardcount = ?,
                        totalkeymousecount = ?,
                        totaltime_fmt      = ?,
                        activetime_fmt     = ?,
                        idletime_fmt       = ?,
                        awaytime_fmt       = ?,
                        status             = ?
                    WHERE id = ?
                    """)) {
                ps.setString(1,  logouttime);
                ps.setInt(2,     total);
                ps.setInt(3,     active);
                ps.setInt(4,     idle);
                ps.setInt(5,     away);
                ps.setInt(6,     keyCount.get());
                ps.setInt(7,     mouseCount.get());
                ps.setString(8,  totalFmt);
                ps.setString(9,  activeFmt);
                ps.setString(10, idleFmt);
                ps.setString(11, awayFmt);
                ps.setString(12, status);
                ps.setLong(13,   id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to update app_config: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Summary
    // ─────────────────────────────────────────────────────────────────

    private void printSummary() {
        int total = activeTime.get() + idleTime.get() + awayTime.get();
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("  Session Summary — {}", username);
        log.info("  ┌──────────────┬──────────┬────────┬────────┐");
        log.info("  │              │  Seconds │    Min │    Hrs │");
        log.info("  ├──────────────┼──────────┼────────┼────────┤");
        log.info("  │ Total time   │ {:>8} │ {:>6} │ {:>6} │",
                total,
                total / 60,
                String.format("%.2f", total / 3600.0));
        log.info("  │ Active time  │ {:>8} │ {:>6} │ {:>6} │",
                activeTime.get(),
                activeTime.get() / 60,
                String.format("%.2f", activeTime.get() / 3600.0));
        log.info("  │ Idle time    │ {:>8} │ {:>6} │ {:>6} │",
                idleTime.get(),
                idleTime.get() / 60,
                String.format("%.2f", idleTime.get() / 3600.0));
        log.info("  │ Away time    │ {:>8} │ {:>6} │ {:>6} │",
                awayTime.get(),
                awayTime.get() / 60,
                String.format("%.2f", awayTime.get() / 3600.0));
        log.info("  └──────────────┴──────────┴────────┴────────┘");
        log.info("  Keystrokes : {}   Mouse clicks : {}",
                keyCount.get(), mouseCount.get());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Getters (for sync payload)
    // ─────────────────────────────────────────────────────────────────

    public String getUsername() { return username; }
    public String getDeviceid() { return deviceid; }

    // ─────────────────────────────────────────────────────────────────
    //  Helper
    // ─────────────────────────────────────────────────────────────────

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