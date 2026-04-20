package com.activepulse.agent.sync;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.monitor.UserStatus;
import com.activepulse.agent.monitor.UserStatusTracker;
import com.activepulse.agent.util.EnvConfig;
import com.activepulse.agent.util.TimeUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.zip.*;

/**
 * SyncManager — syncs activity data and screenshots to server.
 *
 * Reads all config from EnvConfig (agent.env):
 *   SERVER_BASE_URL, API_KEY, USER_ID, ORGANIZATION_ID
 *
 * Endpoint 1 — POST /api/sync/data           (JSON)
 * Endpoint 2 — POST /api/sync/screenshots    (multipart, ≤ 10 MB)
 * Endpoint 3 — POST /api/sync/screenshots/chunk (chunked, > 10 MB)
 *
 * When SERVER_BASE_URL / API_KEY not configured → saves JSON locally.
 *
 * Updated: After successful sync, screenshots are deleted from original folder and not stored in sync folder
 */
public class SyncManager {

    private static final Logger log = LoggerFactory.getLogger(SyncManager.class);

    private static final long THRESHOLD_BYTES  = 10L * 1024 * 1024;
    private static final long CHUNK_SIZE_BYTES =  5L * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile SyncManager instance;
    private SyncManager() {
        // Sync folder creation disabled - no local file storage
        log.info("SyncManager initialized - local sync folder disabled");
    }

    public static SyncManager getInstance() {
        if (instance == null) {
            synchronized (SyncManager.class) {
                if (instance == null) instance = new SyncManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Config helpers — read from EnvConfig
    // ─────────────────────────────────────────────────────────────────

    private String baseUrl()   { return EnvConfig.get("SERVER_BASE_URL", ""); }
    private String apiKey()    { return EnvConfig.get("API_KEY", ""); }
    private int    userId()    { return EnvConfig.getInt("USER_ID", 0); }
    private int    orgId()     { return EnvConfig.getInt("ORGANIZATION_ID", 0); }
    private String agentVer()  { return EnvConfig.get("AGENT_VERSION", "1.0.0"); }

    private boolean isConfigured() {
        return EnvConfig.isSet("SERVER_BASE_URL")
                && EnvConfig.isSet("API_KEY");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Main sync — called by SyncJob
    // ─────────────────────────────────────────────────────────────────

    public void sync() {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("  Sync cycle — server configured: {}", isConfigured());

        String syncId    = "SYNC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String syncStart = TimeUtil.nowIST();
        Connection conn  = DatabaseManager.getInstance().getConnection();

        boolean dataSyncSuccess = false;

        // ── 1. Data sync ──────────────────────────────────────────────
        List<Long> activityIds = new ArrayList<>();
        List<Long> strokeIds   = new ArrayList<>();
        Map<String, Object> dataPayload =
                buildDataPayload(conn, syncId, syncStart, activityIds, strokeIds);

        if (dataPayload != null) {
            if (isConfigured()) {
                dataSyncSuccess = postDataPayload(dataPayload, syncId);
                if (dataSyncSuccess) {
                    markSynced(conn, "activity_log",          activityIds);
                    markSynced(conn, "keyboard_mouse_strokes", strokeIds);
                    log.info("  Data sync completed successfully");
                } else {
                    log.warn("  Data sync failed - skipping screenshot sync");
                }
            } else {
                log.warn("  Server not configured - data not synced and will remain in database");
                dataSyncSuccess = false;
            }
        } else {
            log.info("  No activity data to sync.");
            dataSyncSuccess = true; // No data is considered success
        }

        // ── 2. Screenshot sync ────────────────────────────────────────
        // Only proceed with screenshot sync if data sync was successful
        if (dataSyncSuccess) {
            List<Long>   screenshotIds   = new ArrayList<>();
            List<String> screenshotPaths = new ArrayList<>();
            readScreenshots(conn, screenshotIds, screenshotPaths);

            if (!screenshotPaths.isEmpty()) {
                if (isConfigured()) {
                    Path zipFile = buildZip(screenshotPaths, syncId);
                    if (zipFile != null) {
                        boolean screenshotSyncSuccess = uploadScreenshots(zipFile, syncId, screenshotPaths);

                        if (screenshotSyncSuccess) {
                            markSynced(conn, "screenshots", screenshotIds);
                            log.info("  Screenshot sync completed successfully");
                        } else {
                            log.warn("  Screenshot sync failed");
                        }
                        try { Files.deleteIfExists(zipFile); } catch (IOException ignored) {}
                    }
                } else {
                    log.warn("  Server not configured - screenshots not synced and will remain in database");
                }
            } else {
                log.info("  No screenshots to sync.");
            }
        }

        recordSyncLog(conn, syncId, syncStart, TimeUtil.nowIST());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ─────────────────────────────────────────────────────────────────
    //  End-of-day sync — called at midnight to ensure all data is synced
    // ─────────────────────────────────────────────────────────────────

    public void syncEndOfDay() {
        log.info("🌅 End-of-day sync triggered - ensuring all data for today is synced");
        
        String currentTime = TimeUtil.nowIST();
        String currentDate = currentTime.split(" ")[0];
        
        // Check if this is actually end-of-day (23:55 or later)
        String[] timeParts = currentTime.split(" ")[1].split(":");
        int hour = Integer.parseInt(timeParts[0]);
        
        if (hour < 23) {
            log.info("Skipping end-of-day sync - current time {} is before 23:55", currentTime);
            return;
        }
        
        log.info("Performing end-of-day sync for date: {}", currentDate);
        
        // Force a regular sync to catch any remaining data
        sync();
        
        // Additional check: ensure no unsynced data remains for today
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            
            // Check for any unsynced activity logs for today
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT COUNT(*) as count FROM activity_log 
                    WHERE DATE(recorded_at) = ? AND synced = 0
                    """)) {
                ps.setString(1, currentDate);
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getInt("count") > 0) {
                    log.warn("Found {} unsynced activity records for today - performing additional sync", rs.getInt("count"));
                    sync(); // Try again
                }
            }
            
            // Check for any unsynced strokes for today
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT COUNT(*) as count FROM keyboard_mouse_strokes 
                    WHERE DATE(recorded_at) = ? AND synced = 0
                    """)) {
                ps.setString(1, currentDate);
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getInt("count") > 0) {
                    log.warn("Found {} unsynced stroke records for today - performing additional sync", rs.getInt("count"));
                    sync(); // Try again
                }
            }
            
        } catch (SQLException e) {
            log.error("Error checking for unsynced data during end-of-day sync: {}", e.getMessage());
        }
        
        log.info("🌅 End-of-day sync completed");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Shutdown sync — called before machine shutdown to ensure data safety
    // ─────────────────────────────────────────────────────────────────

    public void syncBeforeShutdown() {
        log.info("🔌 Shutdown sync triggered - ensuring all data is synced before shutdown");
        
        String currentTime = TimeUtil.nowIST();
        log.info("Starting shutdown sync at: {}", currentTime);
        
        try {
            // Force a regular sync to sync all pending data
            sync();
            
            // Wait a moment for sync to complete
            Thread.sleep(2000);
            
            // Final verification: check if any data remains unsynced
            Connection conn = DatabaseManager.getInstance().getConnection();
            
            // Check activity logs
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT COUNT(*) as count FROM activity_log WHERE synced = 0
                    """)) {
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getInt("count") > 0) {
                    log.warn("⚠️  {} activity records remain unsynced after shutdown sync", rs.getInt("count"));
                }
            }
            
            // Check keyboard/mouse strokes
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT COUNT(*) as count FROM keyboard_mouse_strokes WHERE synced = 0
                    """)) {
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getInt("count") > 0) {
                    log.warn("⚠️  {} stroke records remain unsynced after shutdown sync", rs.getInt("count"));
                }
            }
            
            // Check screenshots
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT COUNT(*) as count FROM screenshots WHERE synced = 0
                    """)) {
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getInt("count") > 0) {
                    log.warn("⚠️  {} screenshots remain unsynced after shutdown sync", rs.getInt("count"));
                }
            }
            
        } catch (Exception e) {
            log.error("❌ Error during shutdown sync: {}", e.getMessage());
        }
        
        log.info("🔌 Shutdown sync completed - data should be safe");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Build data payload aligned to API spec
    // ─────────────────────────────────────────────────────────────────

    private Map<String, Object> buildDataPayload(Connection conn, String syncId,
                                                 String syncStart, List<Long> activityIds, List<Long> strokeIds) {
        try {
            var activityLog = readActivityLog(conn, activityIds);
            var strokes     = readStrokes(conn, strokeIds);
            if (activityLog.isEmpty() && strokes.isEmpty()) return null;

            String deviceId = readConfig(conn, "deviceId", "DEV-UNKNOWN");

            var p = new LinkedHashMap<String, Object>();
            p.put("syncId",           syncId);
            p.put("deviceId",         deviceId);
            p.put("syncStartTime",    syncStart);
            p.put("syncEndTime",      TimeUtil.nowIST());
            p.put("sessionStartTime", readConfig(conn, "sessionStart", syncStart));
            p.put("sessionEndTime",   TimeUtil.nowIST());
            p.put("userId",           userId());           // int — required by API
            p.put("organizationId",   orgId());           // int — required by API
            p.put("agentVersion",     agentVer());
            p.put("osType",           normalizeOs(readConfig(conn, "osName", "")));
            p.put("activityLog",      activityLog);
            p.put("keyboardMouseStrokes", strokes);

            log.info("  Payload — activityLog: {}",
                    p);
            return p;
        } catch (Exception e) {
            log.error("buildDataPayload failed: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB readers
    // ─────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> readActivityLog(
            Connection conn, List<Long> ids) throws SQLException {
        var list = new ArrayList<Map<String, Object>>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT id, username, deviceid, starttime, endtime,
                        processname, title, url, duration, activity_type
                 FROM activity_log WHERE synced = 0 ORDER BY starttime
                 """)) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
                var row = new LinkedHashMap<String, Object>();
                row.put("username",     rs.getString("username"));
                row.put("deviceid",     rs.getString("deviceid"));
                row.put("starttime",    rs.getString("starttime"));
                row.put("endtime",      rs.getString("endtime"));
                row.put("processname",  rs.getString("processname"));
                row.put("title",        rs.getString("title"));
                row.put("url",          rs.getString("url"));
                row.put("duration",     rs.getLong("duration"));
                // activityType stored at record time from real tray status
                row.put("activityType", rs.getString("activity_type"));
                list.add(row);
            }
        }
        return list;
    }

    private List<Map<String, Object>> readStrokes(
            Connection conn, List<Long> ids) throws SQLException {
        var list = new ArrayList<Map<String, Object>>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT id, username, deviceid, recorded_at,
                        keyboardcount, keymousecount
                 FROM keyboard_mouse_strokes WHERE synced = 0
                 ORDER BY recorded_at
                 """)) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
                var row = new LinkedHashMap<String, Object>();
                row.put("username",      rs.getString("username"));
                row.put("deviceid",      rs.getString("deviceid"));
                row.put("recordedAt",    rs.getString("recorded_at")); // camelCase per spec
                row.put("keyboardcount", rs.getInt("keyboardcount"));
                row.put("keymousecount", rs.getInt("keymousecount"));
                list.add(row);
            }
        }
        return list;
    }

    private void readScreenshots(Connection conn,
                                 List<Long> ids, List<String> paths) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT id, file_path FROM screenshots
                 WHERE synced = 0 ORDER BY captured_at
                 """)) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
                paths.add(rs.getString("file_path"));
            }
        } catch (SQLException e) {
            log.error("readScreenshots failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  POST /api/sync/data
    // ─────────────────────────────────────────────────────────────────

    private boolean postDataPayload(Map<String, Object> payload, String syncId) {
        try {
            String json = mapper.writeValueAsString(payload);
            String url  = baseUrl() + "/api/sync/data";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey())
                    .POST(BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            log.info("JSON :{}",json);
            log.info("  POST /api/sync/data → HTTP {} body={}", status,
                    truncate(resp.body(), 120));

            if (status == 200) { return true; }
            if (status == 400) {
                log.warn("  Validation error — check syncId/deviceId/userId.");
                return false;
            }
            return false;
        } catch (Exception e) {
            log.error("  postDataPayload error: {}", e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Screenshot ZIP
    // ─────────────────────────────────────────────────────────────────

    private Path buildZip(List<String> paths, String syncId) {
        try {
            // Use temporary directory instead of sync folder
            Path zipPath = Files.createTempFile(syncId + "-screenshots", ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(zipPath)))) {
                for (String fp : paths) {
                    Path src = Paths.get(fp);
                    if (!Files.exists(src)) continue;
                    zos.putNextEntry(new ZipEntry(src.getFileName().toString()));
                    Files.copy(src, zos);
                    zos.closeEntry();
                }
                log.info("  ZIP built: {} files, {} KB",
                        paths.size(), Files.size(zipPath) / 1024);
                return zipPath;
            }
        } catch (IOException e) {
            log.error("  buildZip failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean uploadScreenshots(Path zipFile, String syncId, List<String> screenshotPaths) {
        try {
            long size = Files.size(zipFile);
            return size <= THRESHOLD_BYTES
                    ? uploadSingle(zipFile, syncId, screenshotPaths)
                    : uploadChunked(zipFile, syncId, screenshotPaths);
        } catch (IOException e) {
            log.error("  uploadScreenshots error: {}", e.getMessage());
            return false;
        }
    }

    /** POST /api/sync/screenshots */
    private boolean uploadSingle(Path zipFile, String syncId, List<String> screenshotPaths) {
        try {
            String boundary = "Boundary" + UUID.randomUUID().toString().replace("-", "");
            String url      = baseUrl() + "/api/sync/screenshots";
            String username = AppConfigManager.getInstance().getUsername();

            var body = new ByteArrayOutputStream();
            writeField(body, boundary, "syncId",         syncId);
            writeField(body, boundary, "deviceId",       readConfigDirect("deviceId", "DEV-UNKNOWN"));
            writeField(body, boundary, "username",        username);
            writeField(body, boundary, "userId",          String.valueOf(userId()));
            writeField(body, boundary, "organizationId",  String.valueOf(orgId()));
            writeField(body, boundary, "capturedAt",      TimeUtil.nowIST());
            writeFilePart(body, boundary, "screenshots",
                    zipFile.getFileName().toString(), Files.readAllBytes(zipFile));
            body.write(("--" + boundary + "--\r\n").getBytes());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + apiKey())
                    .POST(BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("  POST /api/sync/screenshots → HTTP {}", resp.statusCode());
            if (resp.statusCode() == 200) {
                deleteOriginalScreenshots(screenshotPaths);
                return true;
            }
            log.warn("  Screenshot upload failed: {}", truncate(resp.body(), 200));
            return false;
        } catch (Exception e) {
            log.error("  uploadSingle error: {}", e.getMessage());
            return false;
        }
    }

    /** POST /api/sync/screenshots/chunk — 5 MB chunks */
    private boolean uploadChunked(Path zipFile, String syncId, List<String> screenshotPaths) {
        try {
            byte[] all         = Files.readAllBytes(zipFile);
            int    totalChunks = (int) Math.ceil((double) all.length / CHUNK_SIZE_BYTES);
            String uploadId    = UUID.randomUUID().toString();
            String url         = baseUrl() + "/api/sync/screenshots/chunk";
            String username    = AppConfigManager.getInstance().getUsername();
            String deviceId    = readConfigDirect("deviceId", "DEV-UNKNOWN");

            log.info("  Chunked upload: {} chunks, {} KB total",
                    totalChunks, all.length / 1024);

            for (int i = 0; i < totalChunks; i++) {
                String boundary  = "Boundary" + UUID.randomUUID().toString().replace("-", "");
                int    start     = (int) (i * CHUNK_SIZE_BYTES);
                int    end       = (int) Math.min(start + CHUNK_SIZE_BYTES, all.length);
                byte[] chunk     = Arrays.copyOfRange(all, start, end);

                var body = new ByteArrayOutputStream();
                writeField(body, boundary, "uploadId",        uploadId);
                writeField(body, boundary, "chunkIndex",      String.valueOf(i));
                writeField(body, boundary, "totalChunks",     String.valueOf(totalChunks));
                writeField(body, boundary, "syncId",          syncId);
                writeField(body, boundary, "deviceId",        deviceId);
                writeField(body, boundary, "username",         username);
                writeField(body, boundary, "userId",           String.valueOf(userId()));
                writeField(body, boundary, "organizationId",   String.valueOf(orgId()));
                writeField(body, boundary, "capturedAt",       TimeUtil.nowIST());
                writeFilePart(body, boundary, "chunk", "chunk_" + i + ".bin", chunk);
                body.write(("--" + boundary + "--\r\n").getBytes());

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Authorization", "Bearer " + apiKey())
                        .POST(BodyPublishers.ofByteArray(body.toByteArray()))
                        .build();

                HttpResponse<String> resp =
                        http.send(req, HttpResponse.BodyHandlers.ofString());
                log.info("  Chunk {}/{} → HTTP {}", i + 1, totalChunks, resp.statusCode());

                if (resp.statusCode() != 200) {
                    log.warn("  Chunk {} failed: {}", i, truncate(resp.body(), 200));
                    return false;
                }
                // Last chunk — final response
                if (i == totalChunks - 1) {
                    log.info("  Chunked upload complete.");
                    deleteOriginalScreenshots(screenshotPaths);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("  uploadChunked error: {}", e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Multipart helpers
    // ─────────────────────────────────────────────────────────────────

    private void writeField(ByteArrayOutputStream out, String boundary,
                            String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes());
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes());
        out.write((value + "\r\n").getBytes());
    }

    private void writeFilePart(ByteArrayOutputStream out, String boundary,
                               String fieldName, String fileName,
                               byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes());
        out.write(("Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + fileName + "\"\r\n").getBytes());
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes());
        out.write(data);
        out.write("\r\n".getBytes());
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB helpers
    // ─────────────────────────────────────────────────────────────────

    private void markSynced(Connection conn, String table, List<Long> ids) {
        if (ids.isEmpty()) return;
        String ph  = "?,".repeat(ids.size());
        String sql = "UPDATE " + table + " SET synced=1 WHERE id IN ("
                + ph.substring(0, ph.length() - 1) + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("markSynced failed for {}: {}", table, e.getMessage());
        }
    }

    private void recordSyncLog(Connection conn, String syncId,
                               String start, String end) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO sync_log
                    (sync_id, sync_start, sync_end, status, response_code)
                VALUES (?, ?, ?, ?, 0)
                """)) {
            ps.setString(1, syncId);
            ps.setString(2, start);
            ps.setString(3, end);
            ps.setString(4, isConfigured() ? "sent" : "local");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("recordSyncLog failed: {}", e.getMessage());
        }
    }

    private String readConfig(Connection conn, String key, String fallback) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM agent_config WHERE key=?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (SQLException ignored) {}
        return fallback;
    }

    private String readConfigDirect(String key, String fallback) {
        return readConfig(DatabaseManager.getInstance().getConnection(), key, fallback);
    }

    private String normalizeOs(String os) {
        if (os == null) return "Unknown";
        String l = os.toLowerCase();
        if (l.contains("win"))   return "Windows";
        if (l.contains("mac"))   return "macOS";
        if (l.contains("linux")) return "Linux";
        return os;
    }

    private String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max) + "...";
    }

    private void deleteOriginalScreenshots(List<String> screenshotPaths) {
        for (String path : screenshotPaths) {
            try {
                Files.deleteIfExists(Paths.get(path));
                log.debug("Deleted original screenshot: {}", path);
            } catch (IOException e) {
                log.warn("Failed to delete original screenshot {}: {}", path, e.getMessage());
            }
        }
    }
}
