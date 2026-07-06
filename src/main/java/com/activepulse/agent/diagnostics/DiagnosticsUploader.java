package com.activepulse.agent.diagnostics;

import com.activepulse.agent.monitor.AppConfigManager;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Uploads agent log files to the portal for the Agent Diagnostics feature.
 *
 * API CONTRACT (matches curl exactly):
 *   POST https://activepulse.portal-login-access.net/api/api/sync/logs
 *   Content-Type: multipart/form-data
 *
 *   Fields:
 *     logs      = <ZIP file, e.g. logs.zip>
 *     username  = "gaytri.sonar"
 *     deviceId  = "DEV-91041AF4-5BB8-11EB-ADD8-9798CA193D00"
 *     syncId    = "sync-a1b2c3d4e5f6"    (lowercase "sync-" + 12 hex chars)
 *
 *   NO date field.
 *
 * FILE LOCATIONS:
 *   Source logs:      %LOCALAPPDATA%\ActivePulse\logs\agent.YYYY-MM-DD.N.log
 *   Sync state:       %LOCALAPPDATA%\ActivePulse\diagnostics-synced-dates.txt
 *   Stub upload dir:  %LOCALAPPDATA%\ActivePulse\diagnostics-pending\
 *   Temp ZIP:         %LOCALAPPDATA%\ActivePulse\logs-YYYY-MM-DD.zip (deleted after upload)
 *
 * CONFIG (agent.env):
 *   DIAGNOSTICS_ENABLED=true
 *   DIAGNOSTICS_UPLOAD_ENDPOINT_READY=false    (true when portal endpoint is live)
 *   DIAGNOSTICS_UPLOAD_CRON=0 0 23 * * ?
 *   SYNC_BASE_URL=https://activepulse.portal-login-access.net
 *
 * SAFETY:
 *   All errors swallowed. Agent NEVER crashes on diagnostics failure.
 */
public final class DiagnosticsUploader {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsUploader.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_CATCHUP_DAYS = 7;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static volatile DiagnosticsUploader instance;

    private final Path stateFile;
    private final HttpClient httpClient;
    private final Set<String> syncedDates = new HashSet<>();

    private DiagnosticsUploader() {
        this.stateFile = PathResolver.dataDir().resolve("diagnostics-synced-dates.txt");
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        loadState();
        log.info("DiagnosticsUploader initialized. Synced dates cached: {}", syncedDates.size());
    }

    public static DiagnosticsUploader getInstance() {
        if (instance == null) {
            synchronized (DiagnosticsUploader.class) {
                if (instance == null) instance = new DiagnosticsUploader();
            }
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC — main entry (called by DiagnosticsJob)
    // ═══════════════════════════════════════════════════════════════

    public synchronized void uploadLogs() {
        try {
            if (!EnvConfig.getBool("DIAGNOSTICS_ENABLED", true)) return;
            List<LocalDate> datesToUpload = findDatesNeedingUpload();
            if (datesToUpload.isEmpty()) return;

            log.info("Diagnostics: uploading {} log file(s): {}",
                    datesToUpload.size(), datesToUpload);
            int successCount = 0;
            for (LocalDate date : datesToUpload) {
                if (uploadOneDay(date)) {
                    syncedDates.add(date.format(DATE_FMT));
                    successCount++;
                    saveState();
                } else {
                    log.warn("Diagnostics upload FAILED for {} — will retry next cycle", date);
                }
            }
            log.info("Diagnostics cycle done: {}/{} uploaded",
                    successCount, datesToUpload.size());
        } catch (Throwable t) {
            log.warn("Diagnostics cycle threw: {}", t.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC — Task Manager kill detection
    // ═══════════════════════════════════════════════════════════════

    public void checkPreviousShutdown() {
        try {
            Path previousLog = findLatestLogBefore(LocalDate.now());
            if (previousLog == null) return;
            String tail = readTail(previousLog, 5000);
            boolean cleanShutdown = tail.contains("Agent stopped.")
                    || tail.contains("Shutdown sync completed.");
            if (!cleanShutdown) {
                log.warn("UNEXPECTED TERMINATION detected: previous session in {} " +
                                "did not shut down cleanly (likely Task Manager kill, crash, or power loss)",
                        previousLog.getFileName());
            }
        } catch (Throwable t) {
            log.debug("checkPreviousShutdown failed: {}", t.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Date discovery
    // ═══════════════════════════════════════════════════════════════

    private List<LocalDate> findDatesNeedingUpload() {
        List<LocalDate> result = new ArrayList<>();
        Path logsDir = PathResolver.logsDir();
        if (!Files.isDirectory(logsDir)) return result;
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusDays(MAX_CATCHUP_DAYS);
        try (Stream<Path> stream = Files.list(logsDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.startsWith("agent.") || !name.endsWith(".log")) return;
                LocalDate fileDate = parseDateFromFilename(name);
                if (fileDate == null || fileDate.isBefore(cutoff) || fileDate.isAfter(today)) return;
                String key = fileDate.format(DATE_FMT);
                if (syncedDates.contains(key)) return;
                if (!result.contains(fileDate)) result.add(fileDate);
            });
        } catch (IOException e) {
            log.debug("Could not list logs dir: {}", e.getMessage());
        }
        result.sort(LocalDate::compareTo);
        return result;
    }

    private LocalDate parseDateFromFilename(String name) {
        try {
            int dot1 = name.indexOf('.');
            if (dot1 < 0) return null;
            int dot2 = name.indexOf('.', dot1 + 1);
            if (dot2 < 0) return null;
            return LocalDate.parse(name.substring(dot1 + 1, dot2), DATE_FMT);
        } catch (Throwable t) { return null; }
    }

    private Path findLatestLogBefore(LocalDate today) throws IOException {
        Path logsDir = PathResolver.logsDir();
        if (!Files.isDirectory(logsDir)) return null;
        try (Stream<Path> stream = Files.list(logsDir)) {
            return stream.filter(p -> {
                String n = p.getFileName().toString();
                if (!n.startsWith("agent.") || !n.endsWith(".log")) return false;
                LocalDate d = parseDateFromFilename(n);
                return d != null && d.isBefore(today);
            }).max((a, b) -> {
                try { return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)); }
                catch (IOException e) { return 0; }
            }).orElse(null);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Upload one day
    // ═══════════════════════════════════════════════════════════════

    private boolean uploadOneDay(LocalDate date) {
        Path zipFile = null;
        try {
            List<Path> files = findLogFilesForDate(date);
            if (files.isEmpty()) return true;

            zipFile = PathResolver.dataDir().resolve("logs-" + date.format(DATE_FMT) + ".zip");
            buildZip(files, zipFile);
            log.info("Built diagnostics ZIP for {}: {} bytes, {} file(s)",
                    date, Files.size(zipFile), files.size());

            // syncId format: lowercase "sync-" + 12 hex chars
            // Example: sync-a1b2c3d4e5f6
            String syncId = "sync-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 12).toLowerCase();

            return sendMultipart(zipFile, date, syncId);
        } catch (Throwable t) {
            log.warn("uploadOneDay({}) failed: {}", date, t.getMessage());
            return false;
        } finally {
            if (zipFile != null) {
                try { Files.deleteIfExists(zipFile); } catch (Throwable ignored) {}
            }
        }
    }

    private List<Path> findLogFilesForDate(LocalDate date) throws IOException {
        List<Path> result = new ArrayList<>();
        String prefix = "agent." + date.format(DATE_FMT) + ".";
        try (Stream<Path> stream = Files.list(PathResolver.logsDir())) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(".log");
            }).sorted().forEach(result::add);
        }
        return result;
    }

    private void buildZip(List<Path> files, Path zipOut) throws IOException {
        Files.createDirectories(zipOut.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipOut)))) {
            for (Path f : files) {
                zos.putNextEntry(new ZipEntry(f.getFileName().toString()));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Multipart POST — real or stub
    // ═══════════════════════════════════════════════════════════════

    private boolean sendMultipart(Path zipFile, LocalDate date, String syncId) {
        boolean endpointReady = EnvConfig.getBool("DIAGNOSTICS_UPLOAD_ENDPOINT_READY", false);
        return endpointReady ? realPost(zipFile, date, syncId) : stubSave(zipFile, date, syncId);
    }

    private boolean stubSave(Path zipFile, LocalDate date, String syncId) {
        try {
            Path pendingDir = PathResolver.dataDir().resolve("diagnostics-pending");
            Files.createDirectories(pendingDir);
            Path destination = pendingDir.resolve(
                    "logs-" + date.format(DATE_FMT) + "-" + syncId + ".zip");
            Files.copy(zipFile, destination, StandardCopyOption.REPLACE_EXISTING);
            pruneStubFiles(pendingDir, 30);
            log.info("[STUB] Diagnostics ZIP saved: {} (date={}, syncId={})",
                    destination.getFileName(), date, syncId);
            return true;
        } catch (Throwable t) {
            log.warn("[STUB] failed: {}", t.getMessage());
            return false;
        }
    }

    /**
     * Sends the multipart POST matching this curl EXACTLY:
     *
     *   curl --location 'https://activepulse.portal-login-access.net/api/api/sync/logs' \
     *     --form 'logs=@"/path/to/logs.zip"' \
     *     --form 'username="gaytri.sonar"' \
     *     --form 'deviceId="DEV-91041AF4-..."' \
     *     --form 'syncId="sync-a1b2c3d4e5f6"'
     *
     * Note the endpoint is /api/api/sync/logs (doubled "api" — confirmed by user).
     */
    private boolean realPost(Path zipFile, LocalDate date, String syncId) {
        try {
            String baseUrl = EnvConfig.get("SYNC_BASE_URL", "").trim();
            if (baseUrl.isBlank()) {
                log.debug("SYNC_BASE_URL not configured — skipping real upload");
                return false;
            }
            // Endpoint uses /api/api/sync/logs (doubled — matches curl contract)
            String endpoint = baseUrl.endsWith("/")
                    ? baseUrl + "api/sync/logs"
                    : baseUrl + "/api/sync/logs";

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

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("User-Agent", "ActivePulse/1.0")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                log.info("Diagnostics uploaded: date={} syncId={} HTTP {}", date, syncId, code);
                return true;
            }
            log.warn("Diagnostics upload got HTTP {}: {}", code, truncate(resp.body(), 200));
            return false;
        } catch (Throwable t) {
            log.debug("realPost failed: {}", t.getMessage());
            return false;
        }
    }

    /**
     * Builds multipart body EXACTLY matching the curl contract:
     *   fields (in order): username, deviceId, syncId, logs
     *   NO date field.
     */
    private byte[] buildMultipartBody(String boundary, String zipFileName, byte[] zipBytes,
                                      String username, String deviceId, String syncId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, boundary, "username", username);
        writeField(out, boundary, "deviceId", deviceId);
        writeField(out, boundary, "syncId",   syncId);
        writeFilePart(out, boundary, "logs", zipFileName, zipBytes);
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void writeField(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFilePart(OutputStream out, String boundary, String fieldName,
                               String fileName, byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fieldName +
                "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/zip\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    // ═══════════════════════════════════════════════════════════════
    // State persistence
    // ═══════════════════════════════════════════════════════════════

    private void loadState() {
        try {
            if (!Files.exists(stateFile)) return;
            for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.isEmpty()) syncedDates.add(s);
            }
        } catch (Throwable t) {
            log.debug("Could not load synced-dates state: {}", t.getMessage());
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(stateFile.getParent());
            List<String> lines = new ArrayList<>(syncedDates);
            Collections.sort(lines);
            if (lines.size() > MAX_CATCHUP_DAYS + 5) {
                lines = lines.subList(lines.size() - (MAX_CATCHUP_DAYS + 5), lines.size());
            }
            Files.write(stateFile, lines, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            log.debug("Could not save synced-dates state: {}", t.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private String readTail(Path file, int maxBytes) throws IOException {
        long size = Files.size(file);
        long from = Math.max(0, size - maxBytes);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(from);
            int toRead = (int) (size - from);
            byte[] buf = new byte[toRead];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private void pruneStubFiles(Path dir, int keepCount) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> all = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".zip")).forEach(all::add);
            if (all.size() <= keepCount) return;
            all.sort((a, b) -> {
                try { return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)); }
                catch (IOException e) { return 0; }
            });
            for (int i = 0; i < all.size() - keepCount; i++) {
                Files.deleteIfExists(all.get(i));
            }
        } catch (Throwable ignored) {}
    }

    private static String stripDomain(String qualified) {
        if (qualified == null || qualified.isBlank()) return "";
        int slash = qualified.lastIndexOf('\\');
        return (slash >= 0) ? qualified.substring(slash + 1) : qualified;
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}