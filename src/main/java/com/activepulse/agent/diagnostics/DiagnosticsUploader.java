package com.activepulse.agent.diagnostics;

import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.util.AgentMode;
import com.activepulse.agent.util.EnvConfig;
import com.activepulse.agent.util.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Uploads agent log files.
 *
 * SCHEDULE (new):
 *   - Shutdown hook  : uploadOnShutdown() — uploads yesterday-if-unsynced +
 *                      today's partial log up to shutdown time.
 *                      Marks shutdownSyncedAt = <today>.
 *   - Daily 12 PM    : uploadDailyFallback() — safety net. Uploads today's
 *                      partial ONLY IF shutdownSyncedAt for today is missing.
 *                      Also backfills any unsynced older days.
 *
 * Time-slicing:
 *   - Today's file uploaded incrementally.
 *     Each upload contains only new bytes since last successful upload.
 *   - Yesterday and older days upload once (full file), marked synced.
 *
 * State file: %LOCALAPPDATA%\ActivePulse\diagnostics-state.properties
 *   Keys:
 *     lastOffset.<yyyy-MM-dd>       = <byte offset already uploaded>
 *     synced.<yyyy-MM-dd>           = true (older complete day done)
 *     shutdownSyncedAt.<yyyy-MM-dd> = <ISO timestamp> (last shutdown sync
 *                                     that covered this date)
 *
 * Slice ZIP naming (inside portal):
 *   agent.YYYY-MM-DD.chunk-NN.log
 *   Where NN = hour_of_day / 2 (00 through 11) for scheduled uploads,
 *   or "shutdown" / "fallback" for those triggers.
 *
 * API CONTRACT unchanged:
 *   POST /api/api/sync/logs
 *   Fields: logs (ZIP), username, deviceId, syncId
 */
public final class DiagnosticsUploader {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsUploader.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_CATCHUP_DAYS = 7;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile DiagnosticsUploader instance;

    private final Path stateFile;
    private final Path heartbeatFile;
    private final HttpClient httpClient;

    // Heartbeat thread: writes current timestamp to heartbeatFile every 60s.
    // Shutdown hook deletes the file to signal a CLEAN exit.
    // If startup finds the file present with a stale timestamp,
    // it means the previous session died uncleanly.
    private static final long HEARTBEAT_INTERVAL_MS  = 60_000;   // write every 60s
    private static final long HEARTBEAT_STALE_MS     = 300_000;  // > 5 min = unclean
    private volatile boolean heartbeatRunning = false;

    // In-memory state (persisted to stateFile)
    private final Map<String, Long>   lastOffsets       = new HashMap<>();  // date -> byte offset
    private final Set<String>         syncedDates       = new HashSet<>();  // complete days done
    private final Map<String, String> shutdownSyncedAt  = new HashMap<>();  // date -> ISO timestamp

    private DiagnosticsUploader() {
        this.stateFile     = PathResolver.dataDir().resolve("diagnostics-state.properties");
        this.heartbeatFile = PathResolver.dataDir().resolve("last-heartbeat.txt");
        this.httpClient    = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        loadState();
        log.info("DiagnosticsUploader initialized. Synced days: {}, tracked offsets: {}, "
                        + "shutdown-synced days: {}",
                syncedDates.size(), lastOffsets.size(), shutdownSyncedAt.size());
    }

    public static DiagnosticsUploader getInstance() {
        if (instance == null) {
            synchronized (DiagnosticsUploader.class) {
                if (instance == null) instance = new DiagnosticsUploader();
            }
        }
        return instance;
    }

    // ═════════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINTS
    // ═════════════════════════════════════════════════════════════════

    /**
     * Called from JVM shutdown hook or when Main.java receives a stop signal.
     *
     * Uploads:
     *   1. Yesterday's log if not yet marked synced
     *   2. Today's partial log (whatever has accumulated so far)
     *
     * After successful today upload, sets shutdownSyncedAt.<today> so the
     * 12 PM fallback the next day knows to skip.
     *
     * Best-effort: any failure is logged; agent shutdown continues.
     */
    public void uploadOnShutdown() {
        if (AgentMode.isTest()) {
            log.info("TEST MODE - diagnostics upload skipped. Logs remain on local disk only.");
            return;
        }
        try {
            log.info("Diagnostics: shutdown upload starting...");
            LocalDate today     = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);

            // 1. Backfill yesterday if not synced
            String yKey = yesterday.format(DATE_FMT);
            if (!syncedDates.contains(yKey)) {
                boolean ok = uploadDay(yesterday, "shutdown");
                log.info("Shutdown backfill for {}: {}", yKey, ok ? "OK" : "FAILED");
            }

            // 2. Upload today's partial
            boolean ok = uploadDay(today, "shutdown");
            if (ok) {
                shutdownSyncedAt.put(today.format(DATE_FMT), LocalDateTime.now().format(TS_FMT));
                saveState();
                log.info("Diagnostics: shutdown upload complete for {}", today);
            } else {
                log.warn("Diagnostics: shutdown upload for {} FAILED (will retry via 12 PM fallback)",
                        today);
            }
        } catch (Throwable t) {
            log.error("uploadOnShutdown crashed: {}", t.getMessage(), t);
        }
    }

    /**
     * Called by Quartz cron at 12:00 PM daily.
     *
     * Behavior:
     *   - If today already has shutdownSyncedAt = today OR was uploaded via
     *     shutdown recently -> skip today's upload (no duplicate).
     *   - Otherwise upload today's partial.
     *   - ALWAYS backfill any unsynced days from the last 7 days.
     */
    public void uploadDailyFallback() {
        if (AgentMode.isTest()) {
            log.info("TEST MODE - diagnostics fallback skipped. Logs remain on local disk only.");
            return;
        }
        try {
            log.info("Diagnostics: 12 PM fallback starting...");
            LocalDate today = LocalDate.now();
            String todayKey = today.format(DATE_FMT);

            // 1. Backfill unsynced older days (up to 7 days back)
            for (int daysBack = 1; daysBack <= MAX_CATCHUP_DAYS; daysBack++) {
                LocalDate d = today.minusDays(daysBack);
                String dKey = d.format(DATE_FMT);
                if (!syncedDates.contains(dKey)) {
                    boolean ok = uploadDay(d, "fallback");
                    log.info("Fallback backfill for {}: {}", dKey, ok ? "OK" : "FAILED");
                }
            }

            // 2. Today's upload — skip if shutdown handler already did it today
            String shutdownStamp = shutdownSyncedAt.get(todayKey);
            if (shutdownStamp != null) {
                log.info("Skipping today's upload — shutdown handler already synced at {}",
                        shutdownStamp);
                return;
            }

            boolean ok = uploadDay(today, "fallback");
            log.info("Fallback upload for {}: {}", todayKey, ok ? "OK" : "FAILED");
        } catch (Throwable t) {
            log.error("uploadDailyFallback crashed: {}", t.getMessage(), t);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // CORE UPLOAD LOGIC
    // ═════════════════════════════════════════════════════════════════

    /**
     * Upload one day's log. For "today", uses time-slicing (new bytes only).
     * For older days, uploads the full file once and marks synced.
     *
     * @param date        the calendar date to upload
     * @param triggerTag  short label included in chunk name ("shutdown", "fallback", etc.)
     * @return true if upload succeeded or nothing new to upload
     */
    private boolean uploadDay(LocalDate date, String triggerTag) {
        String dateKey = date.format(DATE_FMT);

        // Already marked synced (older completed day) — nothing to do
        if (syncedDates.contains(dateKey)) {
            log.debug("Day {} already marked synced, skipping", dateKey);
            return true;
        }

        Path logFile = findLogFile(date);
        if (logFile == null || !Files.exists(logFile)) {
            log.debug("No log file for {} — nothing to upload", dateKey);
            return true; // not a failure — just no data
        }

        boolean isToday = date.equals(LocalDate.now());

        try {
            long currentSize = Files.size(logFile);
            long previousOffset = lastOffsets.getOrDefault(dateKey, 0L);

            // Safety: if file was truncated / rotated, reset offset
            if (previousOffset > currentSize) {
                log.warn("Log file for {} was truncated (offset {} > size {}), resetting to 0",
                        dateKey, previousOffset, currentSize);
                previousOffset = 0;
            }

            long newBytes = currentSize - previousOffset;

            if (isToday) {
                // Time-slice for today
                if (newBytes <= 0) {
                    log.debug("No new log data for today ({} bytes since last upload)", newBytes);
                    return true;
                }

                byte[] slice = readByteRange(logFile, previousOffset, currentSize);
                String chunkTag = buildChunkTag(triggerTag);
                String sliceFileName = String.format("agent.%s.%s.log", dateKey, chunkTag);
                Path zipFile = zipSlice(sliceFileName, slice, dateKey, chunkTag);
                String syncId = "sync-" + UUID.randomUUID().toString().substring(0, 12);

                log.info("Built diagnostics slice for {}: trigger={}, {} new bytes ({} KB), "
                                + "zipped to {} bytes",
                        dateKey, triggerTag, newBytes, newBytes / 1024, Files.size(zipFile));

                boolean ok = sendMultipart(zipFile, date, syncId);
                if (ok) {
                    lastOffsets.put(dateKey, currentSize);
                    saveState();
                }
                try { Files.deleteIfExists(zipFile); } catch (IOException ignored) {}
                return ok;

            } else {
                // Full file upload for older days
                Path zipFile = zipWholeFile(logFile, dateKey);
                String syncId = "sync-" + UUID.randomUUID().toString().substring(0, 12);

                log.info("Built diagnostics ZIP for {}: {} bytes, 1 file(s)",
                        dateKey, Files.size(zipFile));

                boolean ok = sendMultipart(zipFile, date, syncId);
                if (ok) {
                    syncedDates.add(dateKey);
                    lastOffsets.remove(dateKey); // no longer needed
                    saveState();
                }
                try { Files.deleteIfExists(zipFile); } catch (IOException ignored) {}
                return ok;
            }
        } catch (Throwable t) {
            log.warn("uploadDay {} FAILED: {}", dateKey, t.getMessage());
            log.debug("uploadDay exception detail", t);
            return false;
        }
    }

    /**
     * Chunk label written into the slice file name.
     * For shutdown/fallback triggers, use the trigger name.
     * For any other trigger (backward-compat), use hour-based chunk-NN.
     */
    private String buildChunkTag(String triggerTag) {
        if ("shutdown".equals(triggerTag) || "fallback".equals(triggerTag)) {
            // Include time for uniqueness (multiple shutdowns/fallbacks per day possible)
            String hhmm = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmm"));
            return triggerTag + "-" + hhmm;
        }
        // Legacy: chunk-NN by 2-hour window
        int chunkNum = LocalDateTime.now().getHour() / 2;
        return String.format("chunk-%02d", chunkNum);
    }

    // ═════════════════════════════════════════════════════════════════
    // FILE / ZIP HELPERS
    // ═════════════════════════════════════════════════════════════════

    private Path findLogFile(LocalDate date) {
        // Logback pattern: agent.YYYY-MM-DD.N.log (N = rotation index, .0 primary)
        Path logsDir = PathResolver.logsDir();
        if (!Files.isDirectory(logsDir)) return null;
        Path candidate = logsDir.resolve(String.format("agent.%s.0.log", date.format(DATE_FMT)));
        if (Files.exists(candidate)) return candidate;

        // Fall back: look for any agent.<date>.*.log
        try (Stream<Path> paths = Files.list(logsDir)) {
            String prefix = "agent." + date.format(DATE_FMT);
            return paths.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] readByteRange(Path file, long start, long end) throws IOException {
        int len = (int) (end - start);
        byte[] buffer = new byte[len];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(start);
            raf.readFully(buffer);
        }
        return buffer;
    }

    private Path zipSlice(String entryName, byte[] data, String dateKey, String chunkTag)
            throws IOException {
        Path pendingDir = PathResolver.dataDir().resolve("diagnostics-pending");
        Files.createDirectories(pendingDir);
        String zipName = String.format("logs-%s-%s-%s.zip", dateKey, chunkTag,
                UUID.randomUUID().toString().substring(0, 6));
        Path zipFile = pendingDir.resolve(zipName);
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(data);
            zos.closeEntry();
        }
        return zipFile;
    }

    private Path zipWholeFile(Path logFile, String dateKey) throws IOException {
        Path pendingDir = PathResolver.dataDir().resolve("diagnostics-pending");
        Files.createDirectories(pendingDir);
        String zipName = String.format("logs-%s-full-%s.zip", dateKey,
                UUID.randomUUID().toString().substring(0, 6));
        Path zipFile = pendingDir.resolve(zipName);
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
            zos.putNextEntry(new ZipEntry(logFile.getFileName().toString()));
            Files.copy(logFile, zos);
            zos.closeEntry();
        }
        return zipFile;
    }

    // ═════════════════════════════════════════════════════════════════
    // HTTP UPLOAD
    // ═════════════════════════════════════════════════════════════════

    private boolean sendMultipart(Path zipFile, LocalDate date, String syncId) {
        boolean endpointReady = EnvConfig.getBool("DIAGNOSTICS_UPLOAD_ENDPOINT_READY", false);
        if (!endpointReady) {
            // Portal endpoint not live yet — stash to pending folder and return true
            // so state advances (won't repeatedly retry until ready).
            log.info("DIAGNOSTICS_UPLOAD_ENDPOINT_READY=false — stashing ZIP locally at {}",
                    zipFile);
            try {
                Path pendingDir = PathResolver.dataDir().resolve("diagnostics-pending");
                Files.createDirectories(pendingDir);
                Path stashed = pendingDir.resolve(zipFile.getFileName().toString());
                Files.copy(zipFile, stashed, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.warn("Failed to stash ZIP: {}", e.getMessage());
            }
            return true;
        }
        return realPost(zipFile, date, syncId);
    }

    private boolean realPost(Path zipFile, LocalDate date, String syncId) {
        String endpoint = null;
        try {
            String baseUrl = EnvConfig.get("SYNC_BASE_URL", "").trim();
            if (baseUrl.isBlank()) {
                log.warn("SYNC_BASE_URL not configured — cannot upload diagnostics");
                return false;
            }
            endpoint = baseUrl.endsWith("/")
                    ? baseUrl + "api/api/sync/logs"
                    : baseUrl + "/api/api/sync/logs";

            String username = "", deviceId = "";
            try {
                AppConfigManager cfg = AppConfigManager.getInstance();
                username = stripDomain(nullSafe(cfg.getUsername()));
                deviceId = nullSafe(cfg.getDeviceId());
            } catch (Throwable ignored) {}

            String boundary = "Boundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] zipBytes = Files.readAllBytes(zipFile);
            byte[] body = buildMultipartBody(boundary, zipFile.getFileName().toString(),
                    zipBytes, username, deviceId, syncId);

            log.info("═══ Diagnostics HTTP POST ═══");
            log.info("  URL:        {}", endpoint);
            log.info("  Method:     POST");
            log.info("  Content-Type: multipart/form-data; boundary={}", boundary);
            log.info("  User-Agent: ActivePulse/1.0");
            log.info("  ─── Form fields ─────────────");
            log.info("    username  = '{}'", username);
            log.info("    deviceId  = '{}'", deviceId);
            log.info("    syncId    = '{}'", syncId);
            log.info("    logs      = <ZIP file>");
            log.info("      filename: {}", zipFile.getFileName());
            log.info("      size:     {} bytes ({} KB)", zipBytes.length, zipBytes.length / 1024);
            log.info("      date:     {}", date);
            log.info("  Total body size: {} bytes", body.length);
            log.info("═════════════════════════════");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("User-Agent", "ActivePulse/1.0")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            long startMs = System.currentTimeMillis();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = System.currentTimeMillis() - startMs;

            int code = resp.statusCode();
            String responseBody = resp.body() == null ? "" : resp.body();

            log.info("═══ Diagnostics HTTP Response ═══");
            log.info("  Status:     HTTP {}", code);
            log.info("  Elapsed:    {} ms", elapsedMs);
            log.info("  ─── Response headers ────────");
            resp.headers().map().forEach((k, v) ->
                    log.info("    {} = {}", k, String.join(", ", v))
            );
            log.info("  ─── Response body ({} bytes) ─", responseBody.length());
            if (responseBody.isEmpty()) {
                log.info("    <empty>");
            } else if (responseBody.length() <= 500) {
                log.info("    {}", responseBody);
            } else {
                log.info("    {} ...(truncated)", responseBody.substring(0, 500));
            }
            log.info("═════════════════════════════════");

            if (code >= 200 && code < 300) {
                log.info("Diagnostics uploaded: date={} syncId={} HTTP {} in {}ms",
                        date, syncId, code, elapsedMs);
                return true;
            }

            log.warn("Diagnostics upload FAILED: HTTP {} — {}",
                    code, truncate(responseBody, 200));
            return false;

        } catch (Throwable t) {
            String cls = t.getClass().getSimpleName();
            String msg = t.getMessage() == null ? "" : t.getMessage();
            if (cls.contains("SSL") || msg.contains("PKIX")
                    || msg.contains("UnknownHost") || msg.contains("ConnectException")) {
                log.warn("Diagnostics upload deferred: internet unavailable or intercepted " +
                        "(endpoint was {})", endpoint);
            } else {
                log.warn("realPost failed for {}: {} - {}", endpoint, cls, msg);
                log.debug("Full exception:", t);
            }
            return false;
        }
    }

    private byte[] buildMultipartBody(String boundary, String zipFileName, byte[] zipBytes,
                                      String username, String deviceId, String syncId)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, boundary, "username", username);
        writeField(out, boundary, "deviceId", deviceId);
        writeField(out, boundary, "syncId",   syncId);
        writeFilePart(out, boundary, "logs", zipFileName, zipBytes);
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void writeField(OutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeFilePart(OutputStream out, String boundary, String field,
                               String filename, byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + field
                + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/zip\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    // ═════════════════════════════════════════════════════════════════
    // STATE PERSISTENCE
    // ═════════════════════════════════════════════════════════════════

    private void loadState() {
        if (!Files.exists(stateFile)) return;
        try {
            Properties p = new Properties();
            try (var in = Files.newInputStream(stateFile)) { p.load(in); }
            LocalDate cutoff = LocalDate.now().minusDays(MAX_CATCHUP_DAYS);
            for (String key : p.stringPropertyNames()) {
                String val = p.getProperty(key);
                if (key.startsWith("lastOffset.")) {
                    String date = key.substring("lastOffset.".length());
                    if (LocalDate.parse(date).isAfter(cutoff)) {
                        try { lastOffsets.put(date, Long.parseLong(val)); }
                        catch (NumberFormatException ignored) {}
                    }
                } else if (key.startsWith("synced.")) {
                    String date = key.substring("synced.".length());
                    if (LocalDate.parse(date).isAfter(cutoff)) {
                        if ("true".equalsIgnoreCase(val)) syncedDates.add(date);
                    }
                } else if (key.startsWith("shutdownSyncedAt.")) {
                    String date = key.substring("shutdownSyncedAt.".length());
                    if (LocalDate.parse(date).isAfter(cutoff)) {
                        shutdownSyncedAt.put(date, val);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load diagnostics state: {}", e.getMessage());
        }
    }

    private synchronized void saveState() {
        try {
            Files.createDirectories(stateFile.getParent());
            Properties p = new Properties();
            for (var entry : lastOffsets.entrySet()) {
                p.setProperty("lastOffset." + entry.getKey(), String.valueOf(entry.getValue()));
            }
            for (String d : syncedDates) {
                p.setProperty("synced." + d, "true");
            }
            for (var entry : shutdownSyncedAt.entrySet()) {
                p.setProperty("shutdownSyncedAt." + entry.getKey(), entry.getValue());
            }
            try (var out = Files.newOutputStream(stateFile)) {
                p.store(out, "ActivePulse diagnostics upload state");
            }
        } catch (IOException e) {
            log.warn("Failed to save diagnostics state: {}", e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // HEARTBEAT + UNCLEAN-SHUTDOWN DETECTION
    // ═════════════════════════════════════════════════════════════════

    /**
     * Called from Main.java on agent startup (async thread).
     *
     * Detects if the previous session ended uncleanly:
     *   - Heartbeat file exists AND its timestamp is older than 5 min.
     *
     * If so, run uploadOnShutdown() as a catch-up so the previous
     * session's logs get sent even though its shutdown hook never fired.
     *
     * Always starts the heartbeat writer thread on the way out.
     */
    public void checkPreviousShutdown() {
        try {
            if (Files.exists(heartbeatFile)) {
                String content = Files.readString(heartbeatFile).trim();
                LocalDateTime last;
                try {
                    last = LocalDateTime.parse(content, TS_FMT);
                } catch (Exception parseErr) {
                    log.warn("Heartbeat file corrupted (content={}), treating as unclean", content);
                    last = LocalDateTime.MIN;
                }
                long ageMs = Duration.between(last, LocalDateTime.now()).toMillis();
                if (ageMs > HEARTBEAT_STALE_MS) {
                    log.warn("UNCLEAN SHUTDOWN detected — previous heartbeat was {} ({}ms ago). "
                                    + "Running catch-up upload for previous session logs.",
                            content, ageMs);
                    uploadOnShutdown();
                } else {
                    log.info("Heartbeat file recent ({}ms ago) — previous session ended cleanly "
                                    + "or another instance is running.",
                            ageMs);
                }
            } else {
                log.info("No previous heartbeat file — clean startup or first run.");
            }
        } catch (Throwable t) {
            log.warn("checkPreviousShutdown error: {}", t.getMessage());
        } finally {
            startHeartbeat();
        }
    }

    /**
     * Starts a daemon thread that writes the current timestamp to
     * heartbeatFile every 60 seconds. Only one thread ever runs.
     */
    private synchronized void startHeartbeat() {
        if (heartbeatRunning) return;
        heartbeatRunning = true;

        Thread t = new Thread(() -> {
            while (heartbeatRunning) {
                try {
                    Files.createDirectories(heartbeatFile.getParent());
                    Files.writeString(heartbeatFile, LocalDateTime.now().format(TS_FMT));
                } catch (Throwable err) {
                    log.debug("Heartbeat write failed: {}", err.getMessage());
                }
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "activepulse-heartbeat");
        t.setDaemon(true);
        t.start();
        log.info("Heartbeat writer started (interval: {}s, stale threshold: {}s)",
                HEARTBEAT_INTERVAL_MS / 1000, HEARTBEAT_STALE_MS / 1000);
    }

    /**
     * Called from the shutdown hook to signal a CLEAN exit.
     * Deletes the heartbeat file so the next startup won't
     * mistake this as an unclean shutdown.
     */
    public void markCleanShutdown() {
        heartbeatRunning = false;
        try {
            Files.deleteIfExists(heartbeatFile);
            log.debug("Heartbeat file deleted (clean shutdown marker).");
        } catch (Throwable t) {
            log.debug("Failed to delete heartbeat file: {}", t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═════════════════════════════════════════════════════════════════

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static String stripDomain(String qualified) {
        if (qualified == null || qualified.isBlank()) return "";
        int slash = qualified.lastIndexOf('\\');
        return (slash >= 0) ? qualified.substring(slash + 1) : qualified;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // Deprecated alias kept for backward compatibility with existing job classes.
    // Prefer uploadDailyFallback() or uploadOnShutdown() directly.
    @Deprecated
    public void uploadLogs() {
        uploadDailyFallback();
    }
}