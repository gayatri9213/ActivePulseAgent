package com.activepulse.agent.sync;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.util.EnvConfig;
import com.activepulse.agent.util.OsType;
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
import java.util.*;
import java.util.zip.*;

/**
 * SyncManager — syncs activity data and screenshots to server.
 *
 * Endpoints:
 *   POST /api/sync/data               (JSON)
 *   POST /api/sync/screenshots        (multipart, <= 10 MB)
 *   POST /api/sync/screenshots/chunk  (5 MB chunks, > 10 MB total)
 *
 * Config (agent.env): SERVER_BASE_URL, USER_ID, ORGANIZATION_ID, AGENT_VERSION.
 * No auth token — the server is expected to accept anonymous posts from the agent.
 */
public final class SyncManager {

    private static final Logger log = LoggerFactory.getLogger(SyncManager.class);

    private static final String SEP = "========================================";

    private static final long THRESHOLD_BYTES  = 10L * 1024 * 1024;
    private static final long CHUNK_SIZE_BYTES =  5L * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static volatile SyncManager instance;

    private SyncManager() {
        log.info("SyncManager initialized.");
    }

    public static SyncManager getInstance() {
        if (instance == null) {
            synchronized (SyncManager.class) {
                if (instance == null) instance = new SyncManager();
            }
        }
        return instance;
    }

    // ─── Config helpers ──────────────────────────────────────────────

    private String baseUrl()  { return EnvConfig.get("SERVER_BASE_URL", ""); }
    private int    userId()   { return EnvConfig.getInt("USER_ID", 0); }
    private int    orgId()    { return EnvConfig.getInt("ORGANIZATION_ID", 0); }
    private String agentVer() { return EnvConfig.get("AGENT_VERSION", "1.0.0"); }

    private boolean isConfigured() {
        return EnvConfig.isSet("SERVER_BASE_URL");
    }

    // ─── Main sync ───────────────────────────────────────────────────

    public void sync() {
        log.info(SEP);
        log.info("  Sync cycle -- server configured: {}", isConfigured());

        String syncId    = "SYNC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String syncStart = TimeUtil.nowIST();
        Connection conn  = DatabaseManager.getInstance().getConnection();

        boolean dataSyncSuccess;

        // 1. Data
        List<Long> activityIds = new ArrayList<>();
        List<Long> strokeIds   = new ArrayList<>();
        Map<String, Object> dataPayload =
                buildDataPayload(conn, syncId, syncStart, activityIds, strokeIds);

        if (dataPayload != null) {
            if (isConfigured()) {
                dataSyncSuccess = postDataPayload(dataPayload, syncId);
                if (dataSyncSuccess) {
                    markSynced(conn, "activity_log",           activityIds);
                    markSynced(conn, "keyboard_mouse_strokes", strokeIds);
                    log.info("  Data sync completed successfully.");
                } else {
                    log.warn("  Data sync failed - skipping screenshot sync this cycle.");
                }
            } else {
                log.warn("  Server not configured - data held in DB.");
                dataSyncSuccess = false;
            }
        } else {
            log.info("  No activity data to sync.");
            dataSyncSuccess = true;
        }

        // 2. Screenshots
        if (dataSyncSuccess) {
            List<Long>   screenshotIds   = new ArrayList<>();
            List<String> screenshotPaths = new ArrayList<>();
            readScreenshots(conn, screenshotIds, screenshotPaths);

            if (!screenshotPaths.isEmpty()) {
                if (isConfigured()) {
                    Path zipFile = buildZip(screenshotPaths, syncId);
                    if (zipFile != null) {
                        boolean ok = uploadScreenshots(zipFile, syncId, screenshotPaths);
                        if (ok) {
                            markSynced(conn, "screenshots", screenshotIds);
                            log.info("  Screenshot sync completed successfully.");
                        } else {
                            log.warn("  Screenshot sync failed.");
                        }
                        try { Files.deleteIfExists(zipFile); } catch (IOException ignored) {}
                    }
                } else {
                    log.warn("  Server not configured - screenshots held in DB.");
                }
            } else {
                log.info("  No screenshots to sync.");
            }
        }

        recordSyncLog(conn, syncId, syncStart, TimeUtil.nowIST());
        log.info(SEP);
    }

    // ─── End-of-day sync ─────────────────────────────────────────────

    public void syncEndOfDay() {
        log.info("End-of-day sync triggered.");

        String currentTime = TimeUtil.nowIST();
        String currentDate = currentTime.split(" ")[0];
        int hour = Integer.parseInt(currentTime.split(" ")[1].split(":")[0]);

        if (hour < 23) {
            log.info("Skipping end-of-day sync - current time {} is before 23:00", currentTime);
            return;
        }

        log.info("Performing end-of-day sync for date: {}", currentDate);
        sync();

        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            if (hasUnsyncedToday(conn, "activity_log",           currentDate) ||
                    hasUnsyncedToday(conn, "keyboard_mouse_strokes", currentDate)) {
                log.warn("Unsynced records remain for today - running additional sync");
                sync();
            }
        } catch (Exception e) {
            log.error("End-of-day verification failed: {}", e.getMessage());
        }
        log.info("End-of-day sync completed.");
    }

    private boolean hasUnsyncedToday(Connection conn, String table, String date) throws SQLException {
        String sql = "SELECT COUNT(*) AS c FROM " + table
                + " WHERE DATE(recorded_at) = ? AND synced = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt("c") > 0;
        }
    }

    // ─── Shutdown sync ───────────────────────────────────────────────

    public void syncBeforeShutdown() {
        log.info("Shutdown sync triggered.");
        try {
            sync();
            Thread.sleep(2000);
            reportUnsynced("activity_log");
            reportUnsynced("keyboard_mouse_strokes");
            reportUnsynced("screenshots");
        } catch (Exception e) {
            log.error("Shutdown sync failed: {}", e.getMessage());
        }
        log.info("Shutdown sync completed.");
    }

    private void reportUnsynced(String table) {
        String sql = "SELECT COUNT(*) AS c FROM " + table + " WHERE synced = 0";
        try (Statement st = DatabaseManager.getInstance().getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next() && rs.getInt("c") > 0) {
                log.warn("[WARN] {} {} records remain unsynced after shutdown sync",
                        rs.getInt("c"), table);
            }
        } catch (SQLException e) {
            log.debug("reportUnsynced {}: {}", table, e.getMessage());
        }
    }

    // ─── Build data payload ──────────────────────────────────────────

    private Map<String, Object> buildDataPayload(Connection conn, String syncId, String syncStart,
                                                 List<Long> activityIds, List<Long> strokeIds) {
        try {
            var activityLog = readActivityLog(conn, activityIds);
            var strokes     = readStrokes(conn, strokeIds);
            if (activityLog.isEmpty() && strokes.isEmpty()) return null;

            AppConfigManager cfg = AppConfigManager.getInstance();
            String deviceId = readConfig(conn, "deviceId", cfg.getDeviceId());
            String osType   = normalizeOs(readConfig(conn, "osName", OsType.displayName()));

            var p = new LinkedHashMap<String, Object>();
            p.put("syncId",               syncId);
            p.put("deviceId",             deviceId);
            p.put("syncStartTime",        syncStart);
            p.put("syncEndTime",          TimeUtil.nowIST());
            p.put("sessionStartTime",     readConfig(conn, "sessionStart", syncStart));
            p.put("sessionEndTime",       TimeUtil.nowIST());
            p.put("userId",               userId());
            p.put("organizationId",       orgId());
            p.put("agentVersion",         agentVer());
            p.put("osType",               osType);
            p.put("activityLog",          activityLog);
            p.put("keyboardMouseStrokes", strokes);

            log.info("  Payload built -- activity rows: {}, stroke rows: {}",
                    activityLog.size(), strokes.size());
            return p;
        } catch (Exception e) {
            log.error("buildDataPayload failed: {}", e.getMessage());
            return null;
        }
    }

    // ─── DB readers ──────────────────────────────────────────────────

    private List<Map<String, Object>> readActivityLog(Connection conn, List<Long> ids) throws SQLException {
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
                row.put("username",     stripDomain(rs.getString("username")));
                row.put("deviceid",     rs.getString("deviceid"));
                row.put("starttime",    rs.getString("starttime"));
                row.put("endtime",      rs.getString("endtime"));
                row.put("processname",  rs.getString("processname"));
                row.put("title",        rs.getString("title"));
                row.put("url",          rs.getString("url"));
                row.put("duration",     rs.getLong("duration"));
                row.put("activityType", normalizeActivityType(rs.getString("activity_type")));
                list.add(row);
            }
        }
        return list;
    }

    private String normalizeActivityType(String t) {
        if (t == null || t.isBlank()) return "ACTIVE";
        String u = t.trim().toUpperCase();
        return switch (u) {
            case "ACTIVE", "IDLE", "AWAY" -> u;
            case "LOCKED" -> "AWAY";  // server doesn't accept LOCKED
            default       -> "ACTIVE"; // safe default for unknown values
        };
    }

    private String stripDomain(String qualified) {
        if (qualified == null || qualified.isBlank()) return "";
        int slash = qualified.lastIndexOf('\\');
        return (slash >= 0) ? qualified.substring(slash + 1) : qualified;
    }

    private List<Map<String, Object>> readStrokes(Connection conn, List<Long> ids) throws SQLException {
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
                row.put("username",     stripDomain(rs.getString("username")));
                row.put("deviceid",      rs.getString("deviceid"));
                row.put("recordedAt",    rs.getString("recorded_at"));
                row.put("keyboardcount", rs.getInt("keyboardcount"));
                row.put("keymousecount", rs.getInt("keymousecount"));
                list.add(row);
            }
        }
        return list;
    }

    private void readScreenshots(Connection conn, List<Long> ids, List<String> paths) {
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

    // ─── POST /api/sync/data ─────────────────────────────────────────

    private boolean postDataPayload(Map<String, Object> payload, String syncId) {
        try {
            String json = mapper.writeValueAsString(payload);
            String url  = baseUrl() + "/api/sync/data";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            log.debug("  Outgoing JSON: {}", json);

            log.info("  POST /api/sync/data -> HTTP {} body={}", status, truncate(resp.body(), 200));

            if (status == 200) return true;
            if (status == 400) {
                log.warn("  Validation error - check syncId/deviceId/userId.");
                return false;
            }
            return false;
        } catch (Exception e) {
            log.error("  postDataPayload error: {}", e.getMessage());
            return false;
        }
    }

    // ─── Screenshot ZIP + upload ─────────────────────────────────────

    private Path buildZip(List<String> paths, String syncId) {
        try {
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
            }
            log.info("  ZIP built: {} files, {} KB", paths.size(), Files.size(zipPath) / 1024);
            return zipPath;
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

    private boolean uploadSingle(Path zipFile, String syncId, List<String> screenshotPaths) {
        try {
            String boundary = "Boundary" + UUID.randomUUID().toString().replace("-", "");
            String url      = baseUrl() + "/api/sync/screenshots";
            AppConfigManager cfg = AppConfigManager.getInstance();
            String deviceId = readConfigDirect("deviceId", cfg.getDeviceId());

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeField(body, boundary, "syncId",         syncId);
            writeField(body, boundary, "deviceId",       deviceId);
            writeField(body, boundary, "username",       cfg.getUsernameShort());
            writeField(body, boundary, "userId",         String.valueOf(userId()));
            writeField(body, boundary, "organizationId", String.valueOf(orgId()));
            writeField(body, boundary, "capturedAt",     TimeUtil.nowIST());
            writeFilePart(body, boundary, "screenshots",
                    zipFile.getFileName().toString(), Files.readAllBytes(zipFile));
            body.write(("--" + boundary + "--\r\n").getBytes());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("  POST /api/sync/screenshots -> HTTP {}", resp.statusCode());
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

    private boolean uploadChunked(Path zipFile, String syncId, List<String> screenshotPaths) {
        try {
            byte[] all         = Files.readAllBytes(zipFile);
            int    totalChunks = (int) Math.ceil((double) all.length / CHUNK_SIZE_BYTES);
            String uploadId    = UUID.randomUUID().toString();
            String url         = baseUrl() + "/api/sync/screenshots/chunk";
            AppConfigManager cfg = AppConfigManager.getInstance();
            String deviceId    = readConfigDirect("deviceId", cfg.getDeviceId());

            log.info("  Chunked upload: {} chunks, {} KB total", totalChunks, all.length / 1024);

            for (int i = 0; i < totalChunks; i++) {
                String boundary = "Boundary" + UUID.randomUUID().toString().replace("-", "");
                int    start    = (int) (i * CHUNK_SIZE_BYTES);
                int    end      = (int) Math.min(start + CHUNK_SIZE_BYTES, all.length);
                byte[] chunk    = Arrays.copyOfRange(all, start, end);

                ByteArrayOutputStream body = new ByteArrayOutputStream();
                writeField(body, boundary, "uploadId",       uploadId);
                writeField(body, boundary, "chunkIndex",     String.valueOf(i));
                writeField(body, boundary, "totalChunks",    String.valueOf(totalChunks));
                writeField(body, boundary, "syncId",         syncId);
                writeField(body, boundary, "deviceId",       deviceId);
                writeField(body, boundary, "username",       cfg.getUsernameShort());
                writeField(body, boundary, "userId",         String.valueOf(userId()));
                writeField(body, boundary, "organizationId", String.valueOf(orgId()));
                writeField(body, boundary, "capturedAt",     TimeUtil.nowIST());
                writeFilePart(body, boundary, "chunk", "chunk_" + i + ".bin", chunk);
                body.write(("--" + boundary + "--\r\n").getBytes());

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(BodyPublishers.ofByteArray(body.toByteArray()))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                log.info("  Chunk {}/{} -> HTTP {}", i + 1, totalChunks, resp.statusCode());

                if (resp.statusCode() != 200) {
                    log.warn("  Chunk {} failed: {}", i, truncate(resp.body(), 200));
                    return false;
                }
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

    // ─── Multipart helpers ───────────────────────────────────────────

    private void writeField(ByteArrayOutputStream out, String boundary,
                            String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes());
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes());
        out.write((value + "\r\n").getBytes());
    }

    private void writeFilePart(ByteArrayOutputStream out, String boundary,
                               String fieldName, String fileName, byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes());
        out.write(("Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + fileName + "\"\r\n").getBytes());
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes());
        out.write(data);
        out.write("\r\n".getBytes());
    }

    // ─── DB helpers ──────────────────────────────────────────────────

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

    private void recordSyncLog(Connection conn, String syncId, String start, String end) {
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
            if (rs.next()) {
                String v = rs.getString("value");
                return (v == null || v.isBlank()) ? fallback : v;
            }
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

    private void deleteOriginalScreenshots(List<String> paths) {
        for (String path : paths) {
            try {
                Files.deleteIfExists(Paths.get(path));
                log.debug("Deleted original screenshot: {}", path);
            } catch (IOException e) {
                log.warn("Failed to delete {}: {}", path, e.getMessage());
            }
        }
    }
}