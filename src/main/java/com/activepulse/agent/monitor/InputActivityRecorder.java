package com.activepulse.agent.monitor;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.monitor.InputActivityMonitor.InputSnapshot;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/**
 * InputActivityRecorder — flushes input counters every 60s.
 *
 * Writes to:
 *   keyboard_mouse_strokes — per-interval counts
 *   app_config             — accumulated session totals
 */
public class InputActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(InputActivityRecorder.class);
    private static final int IDLE_THRESHOLD = 2; // lower threshold for 20s window

    private Instant intervalStart = Instant.now();

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile InputActivityRecorder instance;
    private InputActivityRecorder() {}

    public static InputActivityRecorder getInstance() {
        if (instance == null) {
            synchronized (InputActivityRecorder.class) {
                if (instance == null) instance = new InputActivityRecorder();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        intervalStart = Instant.now();
        log.info("InputActivityRecorder ready — driven by AgentScheduler.");
    }

    public void stop() {
        log.info("InputActivityRecorder stopping — flushing...");
        flush();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Flush — called by InputFlushJob every 60s
    // ─────────────────────────────────────────────────────────────────

    public void flush() {
        Instant end   = Instant.now();
        Instant start = intervalStart;
        intervalStart = end;

        InputSnapshot snap = InputActivityMonitor.getInstance().snapshotAndReset();
        long totalSecs = end.getEpochSecond() - start.getEpochSecond();

        // Classify interval
        int total   = snap.keyCount() + snap.mouseClicks();
        boolean active  = total >= IDLE_THRESHOLD;

        // Determine current status for time classification
        UserStatus status = UserStatusTracker.getInstance().getStatus();
        int activeSecs  = (status == UserStatus.WORKING || status == UserStatus.NEUTRAL)
                ? (int) totalSecs : 0;
        int idleSecs    = (status == UserStatus.IDLE)  ? (int) totalSecs : 0;
        int awaySecs    = (status == UserStatus.AWAY)  ? (int) totalSecs : 0;

        log.info("InputFlush → keys={} clicks={} active={}s idle={}s away={}s status={}",
                snap.keyCount(), snap.mouseClicks(),
                activeSecs, idleSecs, awaySecs, status.getLabel());

        // Update UserStatusTracker
        UserStatusTracker.getInstance().evaluate(snap.keyCount(), snap.mouseClicks());

        // Write keyboard_mouse_strokes row
        saveStrokes(snap, TimeUtil.toIST(start));

        // Accumulate into app_config session row
        AppConfigManager.getInstance().accumulate(
                activeSecs, idleSecs, awaySecs,
                snap.keyCount(), snap.mouseClicks());
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB write
    // ─────────────────────────────────────────────────────────────────

    private void saveStrokes(InputSnapshot snap, String recordedAt) {
        String username = AppConfigManager.getInstance().getUsername();
        String deviceid = AppConfigManager.getInstance().getDeviceid();

        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO keyboard_mouse_strokes
                        (username, deviceid, recorded_at, keyboardcount, keymousecount)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, username);
                ps.setString(2, deviceid);
                ps.setString(3, recordedAt);
                ps.setInt(4,    snap.keyCount());
                ps.setInt(5,    snap.mouseClicks());
                ps.executeUpdate();
                log.info("Saved strokes → keys={} clicks={}",
                        snap.keyCount(), snap.mouseClicks());
            }
        } catch (SQLException e) {
            log.error("saveStrokes failed: {}", e.getMessage());
        }
    }
}