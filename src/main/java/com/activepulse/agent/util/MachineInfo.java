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
import java.net.URLEncoder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *   - publicIp and IP location: IPGeolocation.io public lookup page
 *   - IP-based fallback:        ip-api.com if the public page cannot be parsed
 *
 * Results cached for 10 minutes (2 min if data couldn't be fully resolved).
 */
public final class MachineInfo {

    private static final Logger log = LoggerFactory.getLogger(MachineInfo.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int HTTP_TIMEOUT_MS = 5_000;
    private static final int PS_TIMEOUT_SEC = 30;
    private static final String IPGEOLOCATION_USER_AGENT = "ActivePulse/1.0";
    private static final Pattern IPGEOLOCATION_DATA_PATTERN = Pattern.compile(
            "id=\"code-json\"[^>]*data-full=\"(.*?)\"",
            Pattern.DOTALL);

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
//     private static boolean tryReverseGeocode(double lat, double lon, Map<String, Object> result) {
//         try {
//             String url = String.format(
//                     "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%f&lon=%f&zoom=14&addressdetails=1",
//                     lat, lon);

//             // Nominatim usage policy requires a meaningful User-Agent with contact info.
//             // TODO: replace the email with your real one before deploying.
//             String userAgent = "ActivePulse/1.0 (contact: support@aress.com)";

//             String response = httpGet(url, userAgent);
//             if (response == null || response.isBlank()) {
//                 log.debug("Nominatim returned empty response");
//                 return false;
//             }

//             JsonNode root = mapper.readTree(response);
//             JsonNode address = root.path("address");
//             if (address.isMissingNode()) {
//                 log.debug("Nominatim response missing 'address' node");
//                 return false;
//             }

//             // Try multiple fields because Nominatim uses different keys depending on area
//             String city = firstNonEmpty(
//         address.path("city").asText(""),
//         address.path("town").asText(""),
//         address.path("village").asText(""),
//         address.path("municipality").asText(""),
//         address.path("suburb").asText(""),
//         address.path("county").asText(""));

//             if (city.isBlank()) {
//                 log.warn("Nominatim returned no city/town/village for {},{}", lat, lon);
//                 return false;
//             }

//             result.put("city",    city);
//             result.put("region",  address.path("state").asText(""));
//             result.put("country", address.path("country").asText(""));
//             result.put("zip",     address.path("postcode").asText(""));
//             String fullAddress = root.path("display_name").asText("");
// result.put("address", fullAddress);
// log.info("Full Address: {}", fullAddress);

//             log.info("Reverse geocoded {},{} -> {}, {}, {}",
//                     lat, lon, city, result.get("region"), result.get("country"));
//             return true;

//         } catch (Exception e) {
//             log.warn("Reverse geocode failed: {}", e.getMessage());
//             return false;
//         }
//     }


        /**
 * Reverse-geocodes lat/lng to city/region/country.
 * Tries Google Geocoding API first (if key configured), falls back to Nominatim.
 * Updates `result` in place. Returns true if at least the city was resolved.
 */
private static boolean tryReverseGeocode(double lat, double lon, Map<String, Object> result) {
    // Try Google first if key is configured
    String apiKey = EnvConfig.get("GOOGLE_GEOCODING_API_KEY", "").trim();
    if (!apiKey.isBlank()) {
        if (tryGoogleGeocode(lat, lon, apiKey, result)) {
            return true;
        }
        log.warn("Google Geocoding failed -- falling back to Nominatim");
    }

    // Fallback: Nominatim (or primary if no Google key)
    return tryNominatimGeocode(lat, lon, result);
}

/**
 * Reverse-geocode via Google Maps Geocoding API.
 * Docs: https://developers.google.com/maps/documentation/geocoding/requests-reverse-geocoding
 */
private static boolean tryGoogleGeocode(double lat, double lon, String apiKey,
                                         Map<String, Object> result) {
    try {
        String url = String.format(
                "https://maps.googleapis.com/maps/api/geocode/json?latlng=%f,%f&key=%s",
                lat, lon, URLEncoder.encode(apiKey, StandardCharsets.UTF_8));

        String response = httpGet(url, "ActivePulse/1.0");
        if (response == null || response.isBlank()) {
            log.debug("Google Geocoding returned empty response");
            return false;
        }

        JsonNode root = mapper.readTree(response);
        String status = root.path("status").asText("");

        if (!"OK".equals(status)) {
            // Common error statuses worth logging:
            // ZERO_RESULTS — coords don't map to any address (e.g. middle of ocean)
            // OVER_QUERY_LIMIT — you've exceeded your billing quota
            // REQUEST_DENIED — API key missing/invalid/restricted
            // INVALID_REQUEST — malformed request
            log.warn("Google Geocoding status={}, message={}",
                    status, root.path("error_message").asText(""));
            return false;
        }

        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            log.warn("Google Geocoding returned no results for {},{}", lat, lon);
            return false;
        }

        // Google returns results in priority order — the first is the most specific.
        // We want to find a "locality" (city) result, or use the first one if not found.
        JsonNode best = results.get(0);
        for (JsonNode candidate : results) {
            if (containsType(candidate.path("types"), "locality")) {
                best = candidate;
                break;
            }
        }

        // Extract address components from the chosen result
        String city = "", region = "", country = "", postalCode = "";
        JsonNode components = best.path("address_components");
        if (components.isArray()) {
            for (JsonNode component : components) {
                JsonNode types = component.path("types");
                String longName = component.path("long_name").asText("");
                String shortName = component.path("short_name").asText("");

                if (containsType(types, "locality") || containsType(types, "postal_town")) {
                    city = longName;
                } else if (containsType(types, "administrative_area_level_1")) {
                    region = longName;
                } else if (containsType(types, "country")) {
                    country = longName;
                } else if (containsType(types, "postal_code")) {
                    postalCode = longName;
                }
            }
        }

        // City might be missing for rural areas; try fallbacks
        if (city.isBlank() && components.isArray()) {
            for (JsonNode component : components) {
                JsonNode types = component.path("types");
                if (containsType(types, "sublocality") ||
                    containsType(types, "administrative_area_level_2") ||
                    containsType(types, "administrative_area_level_3")) {
                    city = component.path("long_name").asText("");
                    if (!city.isBlank()) break;
                }
            }
        }

        if (city.isBlank()) {
            log.warn("Google Geocoding returned no city for {},{}: formatted_address={}",
                    lat, lon, best.path("formatted_address").asText(""));
            return false;
        }

        String formattedAddress = best.path("formatted_address").asText(city);

        result.put("city",    city);
        result.put("region",  region);
        result.put("country", country);
        result.put("zip",     postalCode);
        result.put("address", formattedAddress);

        log.info("Google Geocoded {},{} -> {}, {}, {} (full: {})",
                lat, lon, city, region, country, formattedAddress);
        return true;

    } catch (Exception e) {
        log.warn("Google Geocoding failed: {}", e.getMessage());
        return false;
    }
}

/**
 * Helper to check if a JSON array of strings contains a specific type.
 */
private static boolean containsType(JsonNode typesArray, String type) {
    if (!typesArray.isArray()) return false;
    for (JsonNode t : typesArray) {
        if (type.equals(t.asText(""))) return true;
    }
    return false;
}

/**
 * Reverse-geocodes lat/lng to city/region/country via OpenStreetMap Nominatim.
 * Used as fallback when Google is not available or fails.
 */
private static boolean tryNominatimGeocode(double lat, double lon, Map<String, Object> result) {
    try {
        String url = String.format(
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%f&lon=%f&zoom=14&addressdetails=1",
                lat, lon);
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

        String city = firstNonEmpty(
                address.path("city").asText(""),
                address.path("town").asText(""),
                address.path("village").asText(""),
                address.path("municipality").asText(""),
                address.path("suburb").asText(""),
                address.path("county").asText(""));

        if (city.isBlank()) {
            log.warn("Nominatim returned no city for {},{}", lat, lon);
            return false;
        }

        result.put("city",    city);
        result.put("region",  address.path("state").asText(""));
        result.put("country", address.path("country").asText(""));
        result.put("zip",     address.path("postcode").asText(""));
        result.put("address", root.path("display_name").asText(city));

        log.info("Nominatim geocoded {},{} -> {}, {}, {}",
                lat, lon, city, result.get("region"), result.get("country"));
        return true;

    } catch (Exception e) {
        log.warn("Nominatim geocode failed: {}", e.getMessage());
        return false;
    }
}
    /**
     * IP-based location lookup via IPGeolocation.io's public lookup page.
     * Always sets publicIp; only sets lat/lng/city/etc if overwriteCoords is true.
     */
    private static void tryIpFallback(Map<String, Object> result, boolean overwriteCoords) {
        if (tryIpGeolocationPage(result, overwriteCoords)) {
            return;
        }

        log.warn("IPGeolocation.io page lookup unavailable; using ip-api.com fallback");
        tryIpApiFallback(result, overwriteCoords);
    }

    private static boolean tryIpGeolocationPage(Map<String, Object> result,
                                                boolean overwriteCoords) {
        try {
            String publicIp = httpGet("https://api.ipify.org", IPGEOLOCATION_USER_AGENT);
            if (publicIp == null || publicIp.isBlank()) return false;
            publicIp = publicIp.trim();

            String pageUrl = "https://ipgeolocation.io/what-is-my-ip/"
                    + URLEncoder.encode(publicIp, StandardCharsets.UTF_8);
            String html = httpGet(pageUrl, IPGEOLOCATION_USER_AGENT);
            if (html == null || html.isBlank()) return false;

            Matcher matcher = IPGEOLOCATION_DATA_PATTERN.matcher(html);
            if (!matcher.find()) {
                log.warn("IPGeolocation.io page did not contain its location response");
                return false;
            }

            JsonNode node = mapper.readTree(decodeHtmlAttribute(matcher.group(1)));
            JsonNode location = node.path("location");
            if (location.isMissingNode() || location.isNull()) return false;

            result.put("publicIp", node.path("ip").asText(publicIp));

            String city = location.path("city").asText("");
            String region = location.path("state_prov").asText("");
            String country = location.path("country_name").asText("");
            if (overwriteCoords) {
                result.put("city",      city);
                result.put("region",    region);
                result.put("country",   country);
                result.put("zip",       location.path("zipcode").asText(""));
                result.put("address",   buildAddress(city, region, country));
                result.put("latitude",  location.path("latitude").asDouble(0.0));
                result.put("longitude", location.path("longitude").asDouble(0.0));
            } else if (((String) result.get("city")).isBlank()) {
                result.put("city",    city);
                result.put("region",  region);
                result.put("country", country);
                result.put("zip",     location.path("zipcode").asText(""));
                result.put("address", buildAddress(city, region, country));
            }

            log.info("IPGeolocation.io page resolved {} to {}, {}, {}",
                    result.get("publicIp"), city, region, country);
            return true;
        } catch (Exception e) {
            log.warn("IPGeolocation.io page lookup failed: {}", e.getMessage());
            return false;
        }
    }

    private static void tryIpApiFallback(Map<String, Object> result, boolean overwriteCoords) {
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

    private static String buildAddress(String city, String region, String country) {
        return java.util.stream.Stream.of(city, region, country)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    private static String decodeHtmlAttribute(String value) {
        return value
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
