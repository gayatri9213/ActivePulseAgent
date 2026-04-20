package com.activepulse.agent.sync;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.sync.payload.SyncPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/**
 * SyncPayloadWriter — serializes a SyncPayload to pretty-printed JSON
 * and writes it to ~/.activepulse/sync/ for inspection / future HTTP upload.
 *
 * Also records the sync attempt in the sync_log table.
 * Once HTTP sync is added (Phase 2), this class gains a sendToServer() method.
 */
public class SyncPayloadWriter {

    private static final Logger log = LoggerFactory.getLogger(SyncPayloadWriter.class);

    private final ObjectMapper mapper;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile SyncPayloadWriter instance;

    private SyncPayloadWriter() {
        mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);  // pretty-print
        // Sync folder creation disabled - no local file storage
        log.info("SyncPayloadWriter initialized - local sync folder disabled");
    }

    public static SyncPayloadWriter getInstance() {
        if (instance == null) {
            synchronized (SyncPayloadWriter.class) {
                if (instance == null) instance = new SyncPayloadWriter();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Serializes the payload to JSON, writes to disk,
     * logs to sync_log, then marks all rows as synced.
     */
    public void write(SyncPayload payload) {
        Objects.requireNonNull(payload);
        log.info("Sync payload generated for ID: {}", payload.getSyncId());
        
        // Local file storage disabled - only server sync supported
        log.info("Local sync file storage disabled - payload not saved locally");
        log.info("Payload preview:\n{}", buildPreview(payload));
        
        // Record in sync_log table (method handles SQLException internally)
        recordSyncLog(payload, "success", null);
    }

    // ─────────────────────────────────────────────────────────────────
    //  sync_log
    // ─────────────────────────────────────────────────────────────────

    private void recordSyncLog(SyncPayload payload, String status, String errorMsg) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR REPLACE INTO sync_log
                        (sync_id, sync_start, sync_end, status, error_message)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, payload.getSyncId());
                ps.setString(2, payload.getSyncStartTime());
                ps.setString(3, payload.getSyncEndTime());
                ps.setString(4, status);
                ps.setString(5, errorMsg);
                ps.executeUpdate();
                log.debug("sync_log recorded → syncId={} status={}", payload.getSyncId(), status);
            }
        } catch (SQLException e) {
            log.error("Failed to write sync_log: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    /** One-line summary of what's in the payload — shown in logs. */
    private String buildPreview(SyncPayload p) {
        return String.format(
                "  syncId        : %s%n" +
                        "  deviceId      : %s%n" +
                        "  osType        : %s%n" +
                        "  syncWindow    : %s → %s%n" +
                        "  activityLogs  : %d entries%n" +
                        "  applications  : %d entries%n" +
                        "  websites      : %d entries%n" +
                        "  screenshots   : %d entries%n" +
                        "  systemMetrics : cpu=%.1f%% mem=%.1f%% net=%s",
                p.getSyncId(),
                p.getDeviceId(),
                p.getOsType(),
                p.getSyncStartTime(), p.getSyncEndTime(),
                p.getActivityLogs()  != null ? p.getActivityLogs().size()  : 0,
                p.getApplications()  != null ? p.getApplications().size()  : 0,
                p.getWebsites()      != null ? p.getWebsites().size()      : 0,
                p.getScreenshots()   != null ? p.getScreenshots().size()   : 0,
                p.getSystemMetrics() != null ? p.getSystemMetrics().getCpuUsageAvg()    : 0.0,
                p.getSystemMetrics() != null ? p.getSystemMetrics().getMemoryUsageAvg() : 0.0,
                p.getSystemMetrics() != null ? p.getSystemMetrics().getNetworkStatus()  : "n/a"
        );
    }
}
