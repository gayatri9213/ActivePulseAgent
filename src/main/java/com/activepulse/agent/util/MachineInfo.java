package com.activepulse.agent.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Builds the nested `location` block sent in each sync payload.
 *
 * Output format:
 *   {
 *     "address":   "Pune",
 *     "latitude":  18.511033,
 *     "longitude": 73.925595,
 *     "city":      "Pune",
 *     "region":    "Maharashtra",
 *     "country":   "India",
 *     "zip":       "411001",
 *     "publicIp":  "114.143.178.130",
 *     "privateIp": "192.168.1.45"
 *   }
 *
 * Sources used:
 *   - privateIp:                NetworkInterface enumeration
 *   - latitude / longitude:     Windows Location Services via PowerShell GeoCoordinateWatcher
 *   - city / region / country:  Nominatim reverse geocoding of the precise coords
 *   - publicIp:                 ip-api.com
 *   - IP-based fallback:        ip-api.com fills in lat/lng/city/etc if precise fails
 *
 * Results cached for 10 minutes (2 min if data couldn't be fully resolved).
 */
public final class MachineInfo {

    private static final Logger log = LoggerFactory.getLogger(MachineInfo.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int HTTP_TIMEOUT_MS = 5_000;
    private static final int PS_TIMEOUT_SEC = 30;

    private static final Duration CACHE_TTL_FRESH = Duration.ofMinutes(10);
    private static final Duration CACHE_TTL_STALE = Duration.ofMinutes(2);

    private static volatile Map<String, Object> cachedLocation;
    private static volatile Instant cachedAt;

    private MachineInfo() {}

    // ─── Public API ──────────────────────────────────────────────────

    /**
     * @return the nested `location` object for the sync payload.
     *         Never null; missing fields are empty strings or zero values.
     */
    public static Map<String, Object> getLocationPayload() {
        Instant now = Instant.now();
        if (cachedLocation != null && cachedAt != null) {
            Duration age = Duration.between(cachedAt, now);
            boolean isComplete = !((String) cachedLocation.getOrDefault("city", "")).isBlank();
            Duration ttl = isComplete ? CACHE_TTL_FRESH : CACHE_TTL_STALE;
            if (age.compareTo(ttl) < 0) return cachedLocation;
        }

        Map<String, Object> result = buildLocationPayload();
        cachedLocation = result;
        cachedAt = now;
        return result;
    }

    /**
     * Backward-compatible wrapper around the old SyncManager API.
     * Prefer {@link #getLocationPayload()} for new code.
     */
    @Deprecated
    public static Map<String, Object> getSyncDetails() {
        Map<String, Object> loc = getLocationPayload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("privateIp",       loc.get("privateIp"));
        out.put("publicIp",        loc.get("publicIp"));
        out.put("locationDetails",
                loc.get("city") + ", " + loc.get("region") + ", " + loc.get("country"));
        return out;
    }

    // ─── Payload assembly ────────────────────────────────────────────

    private static Map<String, Object> buildLocationPayload() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address",   "");
        result.put("latitude",  0.0);
        result.put("longitude", 0.0);
        result.put("city",      "");
        result.put("region",    "");
        result.put("country",   "");
        result.put("zip",       "");
        result.put("publicIp",  "");
        result.put("privateIp", getPrivateIp());

        // 1) Precise GPS coordinates from Windows Location Services
        double[] preciseCoords = getPreciseCoordinates();
        boolean hasPreciseCoords = preciseCoords != null;

        if (hasPreciseCoords) {
            result.put("latitude",  preciseCoords[0]);
            result.put("longitude", preciseCoords[1]);
            log.info("Precise coordinates from Windows Location Services: {}, {}",
                    preciseCoords[0], preciseCoords[1]);

            // 2) Reverse-geocode the coords to get city/region/country
            boolean reverseSuccess = tryReverseGeocode(preciseCoords[0], preciseCoords[1], result);
            if (!reverseSuccess) {
                log.warn("Reverse geocoding failed -- precise coords preserved; " +
                         "city/region will come from IP fallback");
            }
        }

        // 3) IP-based fallback always called to fill in publicIp;
        //    also overwrites lat/lng/city/etc if precise wasn't available
        tryIpFallback(result, !hasPreciseCoords);

        return result;
    }

    /**
     * Reverse-geocodes lat/lng to city/region/country via OpenStreetMap Nominatim.
     * Updates `result` in place. Returns true if at least the city was resolved.
     */
    private static boolean tryReverseGeocode(double lat, double lon, Map<String, Object> result) {
        try {
            String url = String.format(
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%f&lon=%f&zoom=14&addressdetails=1",
                    lat, lon);

            // Nominatim usage policy requires a meaningful User-Agent with contact info.
            // TODO: replace the email with your real one before deploying.
            String userAgent = "ActivePulse/1.0 (contact: support@aress.com)";

            String response = httpGet(url, userAgent);
            if (response == null || response.isBlank()) {
                log.debug("Nominatim returned empty response");
                return false;
            }

            JsonNode root = mapper.readTree(response);
            JsonNode address = root.path("address");
            if (address.isMissingNode()) {
                log.debug("Nominatim response missing 'address' node");
                return false;
            }

            // Try multiple fields because Nominatim uses different keys depending on area
            String city = firstNonEmpty(
        address.path("city").asText(""),
        address.path("town").asText(""),
        address.path("village").asText(""),
        address.path("municipality").asText(""),
        address.path("suburb").asText(""),
        address.path("county").asText(""));

            if (city.isBlank()) {
                log.warn("Nominatim returned no city/town/village for {},{}", lat, lon);
                return false;
            }

            result.put("city",    city);
            result.put("region",  address.path("state").asText(""));
            result.put("country", address.path("country").asText(""));
            result.put("zip",     address.path("postcode").asText(""));
            String fullAddress = root.path("display_name").asText("");
result.put("address", fullAddress);
log.info("Full Address: {}", fullAddress);

            log.info("Reverse geocoded {},{} -> {}, {}, {}",
                    lat, lon, city, result.get("region"), result.get("country"));
            return true;

        } catch (Exception e) {
            log.warn("Reverse geocode failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * IP-based location lookup via ip-api.com.
     * Always sets publicIp; only sets lat/lng/city/etc if overwriteCoords is true.
     */
    private static void tryIpFallback(Map<String, Object> result, boolean overwriteCoords) {
        try {
            String response = httpGet(
                    "http://ip-api.com/json/?fields=status,message,country,"
                            + "region,regionName,city,zip,lat,lon,query",
                    null);
            if (response == null || response.isBlank()) return;

            JsonNode node = mapper.readTree(response);
            if (!"success".equals(node.path("status").asText())) {
                log.debug("ip-api.com non-success: {}", node.path("message").asText());
                return;
            }

            // Always set publicIp from ip-api (it returns it as 'query')
            result.put("publicIp", node.path("query").asText(""));

            if (overwriteCoords) {
                String city = node.path("city").asText("");
                result.put("city",      city);
                result.put("region",    node.path("regionName").asText(""));
                result.put("country",   node.path("country").asText(""));
                result.put("zip",       node.path("zip").asText(""));
                result.put("address",   city);
                result.put("latitude",  node.path("lat").asDouble(0.0));
                result.put("longitude", node.path("lon").asDouble(0.0));
                log.info("Using IP-based location: {}, {}, {} (lat={}, lng={})",
                        city, result.get("region"), result.get("country"),
                        result.get("latitude"), result.get("longitude"));
            } else if (((String) result.get("city")).isBlank()) {
                // Precise coords are in place, but Nominatim failed; fill in city/region/country from IP
                result.put("city",    node.path("city").asText(""));
                result.put("region",  node.path("regionName").asText(""));
                result.put("country", node.path("country").asText(""));
                result.put("zip",     node.path("zip").asText(""));
                result.put("address", node.path("city").asText(""));
                log.info("Filled city/region/country from IP, preserved precise coords");
            }
        } catch (Exception e) {
            log.warn("IP geolocation failed: {}", e.getMessage());
        }
    }

    // ─── Private IP discovery ────────────────────────────────────────

    public static String getPrivateIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                for (NetworkInterface ni : Collections.list(ifaces)) {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr.isSiteLocalAddress()
                                && !addr.isLoopbackAddress()
                                && addr.getHostAddress().indexOf(':') < 0) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.debug("getPrivateIp failed: {}", e.getMessage());
            return "";
        }
    }

    // ─── Precise lat/long via Windows Location Services ──────────────

    /**
     * Calls Windows Location Services via PowerShell to get precise GPS coordinates.
     *
     * On macOS/Linux: returns null (no equivalent service yet).
     * On Windows: first invocation may trigger a location permission prompt.
     *
     * Return cases:
     *   - {lat, lng}  : success
     *   - null        : permission denied, location unavailable, timeout, or non-Windows
     */
    private static double[] getPreciseCoordinates() {

    if (!System.getProperty("os.name", "")
            .toLowerCase()
            .contains("win")) {
        return null;
    }

    String psScript = """
        Add-Type -AssemblyName System.Device

        $GeoWatcher = New-Object System.Device.Location.GeoCoordinateWatcher

        try {

            $GeoWatcher.Start()

            $timeout = 30

            while (
                ($GeoWatcher.Status -ne 'Ready') `
                -and ($GeoWatcher.Permission -ne 'Denied') `
                -and ($timeout -gt 0)
            ) {
                Start-Sleep -Seconds 1
                $timeout--
            }

            if ($GeoWatcher.Permission -eq 'Denied') {
                Write-Output 'ACCESS_DENIED'
                return
            }

            if ($GeoWatcher.Status -ne 'Ready') {
                Write-Output 'LOCATION_NOT_AVAILABLE'
                return
            }

            $loc = $GeoWatcher.Position.Location

            if ($loc.IsUnknown) {
                Write-Output 'LOCATION_NOT_AVAILABLE'
                return
            }

            $accuracy = 0

            try {
                $accuracy = $loc.HorizontalAccuracy
            }
            catch {
                $accuracy = 0
            }

            Write-Output (
                $loc.Latitude.ToString() +
                "," +
                $loc.Longitude.ToString() +
                "," +
                $accuracy.ToString()
            )

        }
        finally {
            $GeoWatcher.Stop()
        }
        """;

    Process process = null;

    try {

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command",
                psScript
        );

        pb.redirectErrorStream(true);

        process = pb.start();

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                process.getInputStream(),
                                StandardCharsets.UTF_8));

        String line;
        String output = null;

        while ((line = reader.readLine()) != null) {

            line = line.trim();

            if (!line.isEmpty()) {
                output = line;
            }
        }

        if (!process.waitFor(35, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }

        if (output == null) {
            return null;
        }

        if ("ACCESS_DENIED".equals(output)) {

            log.warn(
                    "Windows Location Service permission denied."
            );

            return null;
        }

        if ("LOCATION_NOT_AVAILABLE".equals(output)) {

            log.warn(
                    "Windows Location Service location unavailable."
            );

            return null;
        }

        String[] parts = output.split(",");

        if (parts.length < 2) {
            return null;
        }

        double latitude =
                Double.parseDouble(parts[0].trim());

        double longitude =
                Double.parseDouble(parts[1].trim());

        double accuracy = 0;

        if (parts.length >= 3) {
            accuracy =
                    Double.parseDouble(parts[2].trim());
        }

        log.info(
                "GPS Coordinates -> Lat={}, Lon={}, Accuracy={}m",
                latitude,
                longitude,
                accuracy
        );

        return new double[]{
                latitude,
                longitude
        };

    }
    catch (Exception ex) {

        log.error(
                "Failed to get precise coordinates",
                ex
        );

        return null;
    }
    finally {

        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
    // ─── HTTP helper ─────────────────────────────────────────────────

    /**
     * Single HTTP GET helper used by both Nominatim and ip-api lookups.
     * Pass a userAgent (required by Nominatim) or null (for ip-api).
     */
    private static String httpGet(String urlString, String userAgent) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(urlString).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            if (userAgent != null) {
                conn.setRequestProperty("User-Agent", userAgent);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                log.debug("HTTP {} from {}", code, urlString);
                return null;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            log.debug("HTTP GET {} failed: {}", urlString, e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}