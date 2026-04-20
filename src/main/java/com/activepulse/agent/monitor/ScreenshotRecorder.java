package com.activepulse.agent.monitor;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.monitor.ScreenshotCapture.CaptureResult;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/**
 * ScreenshotRecorder — stateless worker.
 * Scheduling is now owned by AgentScheduler → ScreenshotJob.
 *
 * captureAndSave() is called by Quartz every 5 minutes.
 */
public class ScreenshotRecorder {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotRecorder.class);

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile ScreenshotRecorder instance;
    private ScreenshotRecorder() {}

    public static ScreenshotRecorder getInstance() {
        if (instance == null) {
            synchronized (ScreenshotRecorder.class) {
                if (instance == null) instance = new ScreenshotRecorder();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        log.info("ScreenshotRecorder ready — driven by AgentScheduler.");
    }

    public void stop() {
        log.info("ScreenshotRecorder stopped.");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Core work — called by ScreenshotJob via Quartz
    // ─────────────────────────────────────────────────────────────────

    public void captureAndSave() {
        String capturedAt = TimeUtil.nowIST();
        CaptureResult result = ScreenshotCapture.getInstance().capture();
        if (result == null) {
            log.warn("Screenshot capture returned null — skipping DB write.");
            return;
        }
        saveToDb(result, capturedAt);
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB write
    // ─────────────────────────────────────────────────────────────────

    private void saveToDb(CaptureResult result, String capturedAt) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO screenshots
                        (file_name, file_path, file_size_bytes, captured_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                ps.setString(1, result.fileName());
                ps.setString(2, result.filePath());
                ps.setLong(3,   result.fileSizeBytes());
                ps.setString(4, capturedAt);
                ps.executeUpdate();
                log.info("Saved screenshot → file='{}' size={}KB at={}",
                        result.fileName(), result.fileSizeBytes() / 1024, capturedAt);
            }
        } catch (SQLException e) {
            log.error("Failed to save screenshot metadata: {}", e.getMessage());
        }
    }
}