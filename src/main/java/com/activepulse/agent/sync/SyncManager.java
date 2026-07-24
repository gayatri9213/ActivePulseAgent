package com.activepulse.agent.sync;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.util.*;
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
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.NoRouteToHostException;
import java.net.http.HttpTimeoutException;

/**
 * SyncManager — syncs activity data and screenshots to server.
 *
 * MEMORY-SAFE VERSION:
 *   - Single HttpClient (reused)
 *   - No System.out.println of payloads (was leaking to stdout buffer)
 *   - Screenshot uploads use streamed file bodies (no full-file byte arrays)
 *   - Large buffers are nulled after use to help GC reclaim direct memory
 *   - JSON payload writes to bytes once, released after send
 */
public final class SyncManager {

    private static final Logger log = LoggerFactory.getLogger(SyncManager.class);

    private static final String SEP = "========================================";

    private static final long THRESHOLD_BYTES  = 10L * 1024 * 1024;
    private static final long CHUNK_SIZE_BYTES =  5L * 1024 * 1024;

    private static final int MAX_ACTIVITY_ITEMS = 5000;
    private static final int MAX_STROKE_ITEMS   = 5000;

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
        if (AgentMode.isTest()) {
            log.warn("TEST MODE - sync disabled. Data is captured to the test DB "
                    + "but will NOT be uploaded to the portal.");
            return;
        }
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
                if (dataSyncSuccess &&
                        AppConfigManager.getInstance().isScreenshotsEnabled()) {
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

        // Explicitly release the payload map so its inner Lists can be GC'd
        // before we build the next big object.
        dataPayload = null;
        activityIds = null;
        strokeIds   = null;

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
            Map<String, Object> location = MachineInfo.getLocationPayload();

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
            p.put("location",             location);
            p.put("activityLog",          activityLog);
            p.put("keyboardMouseStrokes", strokes);

            log.info("  Sync location -- city: {}, lat: {}, lng: {}, privateIp: {}, publicIp: {}",
                    location.get("city"),
                    location.get("latitude"),
                    location.get("longitude"),
                    location.get("privateIp"),
                    location.get("publicIp"));
//            log.info("  Payload built -- activity rows: {}, stroke rows: {}",
//                    activityLog.size(), strokes.size());
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
                row.put("username",      stripDomain(rs.getString("username")));
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

    // ─── POST data (JSON) — memory-safe ──────────────────────────────

    @SuppressWarnings("unchecked")
    private boolean postDataPayload(Map<String, Object> payload, String syncId) {
        try {
            List<Map<String, Object>> activityLog =
                    (List<Map<String, Object>>) payload.getOrDefault(
                            "activityLog", Collections.emptyList());

            List<Map<String, Object>> strokes =
                    (List<Map<String, Object>>) payload.getOrDefault(
                            "keyboardMouseStrokes", Collections.emptyList());

            int activityChunks =
                    (int) Math.ceil((double) activityLog.size() / MAX_ACTIVITY_ITEMS);
            int strokeChunks =
                    (int) Math.ceil((double) strokes.size() / MAX_STROKE_ITEMS);
            int totalChunks = Math.max(Math.max(activityChunks, strokeChunks), 1);

            log.info("Splitting sync into {} requests", totalChunks);

            for (int i = 0; i < totalChunks; i++) {

                int activityStart = i * MAX_ACTIVITY_ITEMS;
                int activityEnd   = Math.min(activityStart + MAX_ACTIVITY_ITEMS, activityLog.size());

                int strokeStart = i * MAX_STROKE_ITEMS;
                int strokeEnd   = Math.min(strokeStart + MAX_STROKE_ITEMS, strokes.size());

                List<Map<String,Object>> activityChunk =
                        activityStart < activityLog.size()
                                ? activityLog.subList(activityStart, activityEnd)
                                : Collections.emptyList();

                List<Map<String,Object>> strokeChunk =
                        strokeStart < strokes.size()
                                ? strokes.subList(strokeStart, strokeEnd)
                                : Collections.emptyList();

                Map<String,Object> chunkPayload = new LinkedHashMap<>(payload);
                chunkPayload.put("syncId",               syncId + "-PART-" + (i + 1));
                chunkPayload.put("activityLog",          activityChunk);
                chunkPayload.put("keyboardMouseStrokes", strokeChunk);

                // Serialize to bytes (not String) so we can null it after send
                byte[] jsonBytes = mapper.writeValueAsBytes(chunkPayload);
                chunkPayload = null; // release the map reference immediately

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/api/sync/data"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofByteArray(jsonBytes))
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

                int status = resp.statusCode();
                String body = resp.body();


//                log.info("POST /api/sync/data chunk {}/{} -> HTTP {} ({} bytes response)",
//                        (i + 1), totalChunks, status, body == null ? 0 : body.length());

                if (status == 200 && body != null && !body.isBlank()) {

                    SyncResponse response = mapper.readValue(body, SyncResponse.class);

                    if (response.getSettings() != null) {

                        AppConfigManager config = AppConfigManager.getInstance();

                        config.setLogsEnabled(response.getSettings().isLogsEnabled());
                        config.setScreenshotsEnabled(response.getSettings().isScreenshotsEnabled());

                        log.info("Server Settings -> logsEnabled={}, screenshotsEnabled={}",
                                config.isLogsEnabled(),
                                config.isScreenshotsEnabled());
                    }
                }
                if (status != 200) {
                    log.warn("Chunk {} failed: {}", (i + 1), truncate(body, 300));
                    return false;
                }
            }
            return true;

        } catch (Exception e) {
            String friendly = classifyNetworkError(e);
            if (friendly != null) {
                log.warn("Sync deferred: {} (will retry next cycle)", friendly);
            } else {
                log.error("postDataPayload error: {}", e.getMessage(), e);
            }
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

    // ─── uploadSingle — streams the ZIP file directly, no full-buffer copy ─

    private boolean uploadSingle(Path zipFile, String syncId, List<String> screenshotPaths) {
        try {
            String boundary = "Boundary" + UUID.randomUUID().toString().replace("-", "");
            String url      = baseUrl() + "/api/sync/screenshots";
            AppConfigManager cfg = AppConfigManager.getInstance();
            String deviceId = readConfigDirect("deviceId", cfg.getDeviceId());

            // Build the multipart body as a sequence of parts:
            //   1. header block with form fields
            //   2. the ZIP file streamed from disk
            //   3. closing boundary
            byte[] headerBytes = buildMultipartHeader(boundary, syncId, deviceId, cfg);
            byte[] fileHeader  = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"screenshots\"; filename=\""
                    + zipFile.getFileName() + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes();
            byte[] closer      = ("\r\n--" + boundary + "--\r\n").getBytes();

            HttpRequest.BodyPublisher body = concatPublishers(
                    BodyPublishers.ofByteArray(headerBytes),
                    BodyPublishers.ofByteArray(fileHeader),
                    BodyPublishers.ofFile(zipFile),        // ← streams from disk!
                    BodyPublishers.ofByteArray(closer)
            );

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(body)
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            String respBody = resp.body();

            log.info("  POST /api/sync/screenshots -> HTTP {}", status);
            if (status == 200) {
                deleteOriginalScreenshots(screenshotPaths);
                return true;
            }
            log.warn("  Screenshot upload failed: {}", truncate(respBody, 200));
            return false;
        } catch (Exception e) {
            log.error("  uploadSingle error: {}", e.getMessage());
            return false;
        }
    }

    // ─── uploadChunked — reads only ONE chunk at a time from disk ────
    // Instead of loading the whole ZIP into memory, we open a RandomAccessFile
    // and read one chunk-worth of bytes at each iteration. Memory footprint
    // is bounded to ~CHUNK_SIZE_BYTES regardless of total ZIP size.

    private boolean uploadChunked(Path zipFile, String syncId, List<String> screenshotPaths) {
        try (RandomAccessFile raf = new RandomAccessFile(zipFile.toFile(), "r")) {
            long total       = raf.length();
            int totalChunks  = (int) Math.ceil((double) total / CHUNK_SIZE_BYTES);
            String uploadId  = UUID.randomUUID().toString();
            String url       = baseUrl() + "/api/sync/screenshots/chunk";
            AppConfigManager cfg = AppConfigManager.getInstance();
            String deviceId  = readConfigDirect("deviceId", cfg.getDeviceId());

            log.info("  Chunked upload: {} chunks, {} KB total", totalChunks, total / 1024);

            for (int i = 0; i < totalChunks; i++) {
                String boundary = "Boundary" + UUID.randomUUID().toString().replace("-", "");
                long start = i * CHUNK_SIZE_BYTES;
                long end   = Math.min(start + CHUNK_SIZE_BYTES, total);
                int  chunkSize = (int) (end - start);

                // Read only this chunk from disk (bounded memory)
                byte[] chunk = new byte[chunkSize];
                raf.seek(start);
                raf.readFully(chunk);

                // Build multipart body ONCE per chunk, using ByteArrayOutputStream
                // limited to ~5 MB. Body is released after send.
                ByteArrayOutputStream body = new ByteArrayOutputStream(chunkSize + 4096);
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

                byte[] bodyBytes = body.toByteArray();
                body = null;   // release the ByteArrayOutputStream
                chunk = null;  // release the chunk byte[] too

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(BodyPublishers.ofByteArray(bodyBytes))
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                String respBody = resp.body();

                // Release the body bytes as soon as send completes
                bodyBytes = null;

                log.info("  Chunk {}/{} -> HTTP {}", i + 1, totalChunks, status);

                if (status != 200) {
                    log.warn("  Chunk {} failed: {}", i, truncate(respBody, 200));
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

    /**
     * Builds ONLY the form-field portion of a multipart body (no file part,
     * no closing boundary). Used by uploadSingle() so the file can be
     * streamed separately via BodyPublishers.ofFile().
     */
    private byte[] buildMultipartHeader(String boundary, String syncId, String deviceId,
                                        AppConfigManager cfg) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(1024);
        writeField(body, boundary, "syncId",         syncId);
        writeField(body, boundary, "deviceId",       deviceId);
        writeField(body, boundary, "username",       cfg.getUsernameShort());
        writeField(body, boundary, "userId",         String.valueOf(userId()));
        writeField(body, boundary, "organizationId", String.valueOf(orgId()));
        writeField(body, boundary, "capturedAt",     TimeUtil.nowIST());
        return body.toByteArray();
    }

    /**
     * Concatenates several BodyPublishers into one. Uses the sum of their
     * content lengths so the server sees a correct Content-Length header.
     */
    private HttpRequest.BodyPublisher concatPublishers(HttpRequest.BodyPublisher... parts) {
        return HttpRequest.BodyPublishers.concat(parts);
    }

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

    /**
     * Classifies a network exception into a human-friendly message.
     * Returns null if not a recognized network issue.
     */
    private String classifyNetworkError(Throwable t) {
        Throwable cause = t;
        int depth = 0;
        while (cause != null && depth < 5) {
            String cn = cause.getClass().getSimpleName();
            String msg = cause.getMessage() == null ? "" : cause.getMessage();

            if (cause instanceof SSLHandshakeException
                    || msg.contains("PKIX path building failed")
                    || msg.contains("unable to find valid certification path")
                    || msg.contains("certificate_unknown")) {
                return "internet unavailable or intercepted (SSL handshake failed - possibly captive WiFi or proxy)";
            }
            if (cause instanceof UnknownHostException) {
                return "internet disconnected (DNS lookup failed)";
            }
            if (cause instanceof ConnectException) {
                return "internet disconnected (cannot reach server)";
            }
            if (cause instanceof NoRouteToHostException) {
                return "network unreachable";
            }
            if (cause instanceof HttpTimeoutException) {
                return "server timeout (network slow or server busy)";
            }
            if (cn.equals("SocketTimeoutException")) {
                return "connection timed out";
            }
            if (cn.equals("SocketException") && msg.toLowerCase().contains("connection reset")) {
                return "connection reset by network";
            }

            cause = cause.getCause();
            depth++;
        }
        return null;
    }
}

