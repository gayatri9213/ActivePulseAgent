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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the nested `location` block sent in each sync payload.
 *
 * v1.0.3 CHANGES:
 *  - Office subnet override now supports multiple offices (Pune + Nashik + more)
 *  - All subnets configurable via agent.env (no hardcoded lists)
 *  - Defaults match Aress's known subnets
 *  - PowerShell completely removed (AVG IDP.HELU.PSE79 fix)
 *
 * Subnet → office mapping (defaults; override in agent.env):
 *   PUNE:    192.168.30
 *   NASHIK:  192.168.210, 192.168.137, 192.168.8, 192.168.9
 *   EXTRA:   192.168.70, 192.168.60  (assigned to OFFICE_EXTRA_* — set the
 *                                     OFFICE_EXTRA_* env vars to whichever
 *                                     city these subnets actually belong to)
 *
 * Output format:
 *   {
 *     "address":   "Pune, Maharashtra, India",
 *     "latitude":  18.511033,
 *     "longitude": 73.925595,
 *     "city":      "Pune",
 *     "region":    "Maharashtra",
 *     "country":   "India",
 *     "zip":       "",
 *     "publicIp":  "114.143.178.130",
 *     "privateIp": "192.168.30.116"
 *   }
 */
public final class MachineInfo {

    private static final Logger log = LoggerFactory.getLogger(MachineInfo.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int HTTP_TIMEOUT_MS = 5_000;
    private static final String IPGEOLOCATION_USER_AGENT = "ActivePulse/1.0";
    private static final Pattern IPGEOLOCATION_DATA_PATTERN = Pattern.compile(
            "id=\"code-json\"[^>]*data-full=\"(.*?)\"", Pattern.DOTALL);

    private static final Duration CACHE_TTL_FRESH = Duration.ofMinutes(30);
    private static final Duration CACHE_TTL_STALE = Duration.ofMinutes(5);

    private static volatile Map<String, Object> cachedLocation;
    private static volatile Instant cachedAt;

    private MachineInfo() {}

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

        // 1) OFFICE OVERRIDE — runs FIRST. If private IP matches a known
        //    office subnet, force those coords. No geocoding wait, no
        //    wrong-Mumbai problem.
        boolean isOffice = applyOfficeOverride(result);

        // 2) For WFH users, try Google Geolocation (skipped if at office).
        if (!isOffice) {
            double[] preciseCoords = getCoordinatesFromGoogleGeolocation();
            if (preciseCoords != null) {
                result.put("latitude",  preciseCoords[0]);
                result.put("longitude", preciseCoords[1]);
                log.info("Google Geolocation -> {}, {}", preciseCoords[0], preciseCoords[1]);
                tryReverseGeocode(preciseCoords[0], preciseCoords[1], result);
            }
        }

        // 3) IP fallback ALWAYS runs to record publicIp.
        //    Only overwrites city/coords if we don't already have them.
        boolean cityBlank = ((String) result.get("city")).isBlank();
        tryIpFallback(result, cityBlank && !isOffice);

        return result;
    }

    // ─── OFFICE SUBNET OVERRIDE ──────────────────────────────────────

    /**
     * Checks the private IP against known office subnets and overrides
     * location with authoritative office coordinates if matched.
     *
     * Three offices supported via agent.env:
     *   OFFICE_PUNE_SUBNETS
     *   OFFICE_NASHIK_SUBNETS
     *   OFFICE_EXTRA_SUBNETS (and OFFICE_EXTRA_CITY/REGION/COUNTRY/LAT/LNG)
     *
     * Subnet matching uses prefix on first 3 octets, comma-separated.
     * Trailing dot is auto-added so "192.168.30" matches "192.168.30.X" only.
     *
     * @return true if private IP matched a known office, false otherwise
     */
    private static boolean applyOfficeOverride(Map<String, Object> result) {
        String privateIp = (String) result.get("privateIp");
        if (privateIp == null || privateIp.isBlank()) return false;

        // PUNE — Aress main office
        String puneSubnets = EnvConfig.get("OFFICE_PUNE_SUBNETS", "192.168.30").trim();
        if (matchesAnySubnet(privateIp, puneSubnets)) {
            result.put("city",      "Pune");
            result.put("region",    "Maharashtra");
            result.put("country",   "India");
            result.put("address",   "Pune, Maharashtra, India");
            result.put("latitude",  EnvConfig.getDouble("OFFICE_PUNE_LAT", 18.511033));
            result.put("longitude", EnvConfig.getDouble("OFFICE_PUNE_LNG", 73.925595));
            log.info("Office subnet match: {} → Pune (forced)", privateIp);
            return true;
        }

        // NASHIK — multiple subnets per Suraj's office discovery
        String nashikSubnets = EnvConfig.get(
                "OFFICE_NASHIK_SUBNETS",
                "192.168.210,192.168.137,192.168.8,192.168.9").trim();
        if (matchesAnySubnet(privateIp, nashikSubnets)) {
            result.put("city",      "Nashik");
            result.put("region",    "Maharashtra");
            result.put("country",   "India");
            result.put("address",   "Nashik, Maharashtra, India");
            result.put("latitude",  EnvConfig.getDouble("OFFICE_NASHIK_LAT", 19.9975));
            result.put("longitude", EnvConfig.getDouble("OFFICE_NASHIK_LNG", 73.7898));
            log.info("Office subnet match: {} → Nashik (forced)", privateIp);
            return true;
        }

        // EXTRA — for unknown-city subnets (192.168.70, 192.168.60).
        // Defaults to Pune coords. Reassign in agent.env if these are a
        // different office.
        String extraSubnets = EnvConfig.get(
                "OFFICE_EXTRA_SUBNETS",
                "192.168.70,192.168.60").trim();
        if (matchesAnySubnet(privateIp, extraSubnets)) {
            String city    = EnvConfig.get("OFFICE_EXTRA_CITY",    "Pune");
            String region  = EnvConfig.get("OFFICE_EXTRA_REGION",  "Maharashtra");
            String country = EnvConfig.get("OFFICE_EXTRA_COUNTRY", "India");
            result.put("city",      city);
            result.put("region",    region);
            result.put("country",   country);
            result.put("address",   city + ", " + region + ", " + country);
            result.put("latitude",  EnvConfig.getDouble("OFFICE_EXTRA_LAT", 18.511033));
            result.put("longitude", EnvConfig.getDouble("OFFICE_EXTRA_LNG", 73.925595));
            log.info("Office subnet match: {} → {} (extra, forced)", privateIp, city);
            return true;
        }

        log.debug("Private IP {} did not match any known office subnet", privateIp);
        return false;
    }

    /**
     * Returns true if {@code privateIp} starts with any of the comma-separated
     * subnet prefixes in {@code subnetsCsv}.
     *
     * Accepts both formats:
     *   "192.168.30"          (prefix-only)
     *   "192.168.30.0/24"     (CIDR — only first 3 octets used for matching)
     *
     * Each prefix is matched as "prefix." (with trailing dot) to avoid
     * "192.168.3" wrongly matching "192.168.30.x".
     */
    private static boolean matchesAnySubnet(String privateIp, String subnetsCsv) {
        if (subnetsCsv == null || subnetsCsv.isBlank()) return false;
        for (String prefix : subnetsCsv.split(",")) {
            String p = prefix.trim();
            if (p.isEmpty()) continue;
            // Strip CIDR suffix if present (/24 etc.)
            int slash = p.indexOf('/');
            if (slash > 0) {
                p = p.substring(0, slash);
            }
            // Strip trailing .0 if present (CIDR network format)
            if (p.endsWith(".0")) {
                p = p.substring(0, p.length() - 2);
            }
            // Ensure trailing dot to prevent "192.168.3" matching "192.168.30.x"
            if (!p.endsWith(".")) p = p + ".";
            if (privateIp.startsWith(p)) return true;
        }
        return false;
    }

    // ─── Reverse geocoding ───────────────────────────────────────────

    private static boolean tryReverseGeocode(double lat, double lon, Map<String, Object> result) {
        String apiKey = EnvConfig.get("GOOGLE_GEOCODING_API_KEY", "").trim();
        if (!apiKey.isBlank()) {
            if (tryGoogleGeocode(lat, lon, apiKey, result)) return true;
            log.warn("Google Geocoding failed -- falling back to Nominatim");
        }
        return tryNominatimGeocode(lat, lon, result);
    }

    private static boolean tryGoogleGeocode(double lat, double lon, String apiKey,
                                             Map<String, Object> result) {
        try {
            String url = String.format(
                    "https://maps.googleapis.com/maps/api/geocode/json?latlng=%f,%f&key=%s",
                    lat, lon, URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
            String response = httpGet(url, "ActivePulse/1.0");
            if (response == null || response.isBlank()) return false;

            JsonNode root = mapper.readTree(response);
            if (!"OK".equals(root.path("status").asText(""))) {
                log.warn("Google Geocoding status={}", root.path("status").asText(""));
                return false;
            }

            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) return false;

            JsonNode best = results.get(0);
            for (JsonNode candidate : results) {
                if (containsType(candidate.path("types"), "locality")) {
                    best = candidate;
                    break;
                }
            }

            String city = "", region = "", country = "", postalCode = "";
            JsonNode components = best.path("address_components");
            if (components.isArray()) {
                for (JsonNode component : components) {
                    JsonNode types = component.path("types");
                    String longName = component.path("long_name").asText("");
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

            if (city.isBlank() && components.isArray()) {
                for (JsonNode component : components) {
                    JsonNode types = component.path("types");
                    if (containsType(types, "sublocality") ||
                        containsType(types, "administrative_area_level_2")) {
                        city = component.path("long_name").asText("");
                        if (!city.isBlank()) break;
                    }
                }
            }

            if (city.isBlank()) return false;

            result.put("city",    city);
            result.put("region",  region);
            result.put("country", country);
            result.put("zip",     postalCode);
            result.put("address", best.path("formatted_address").asText(city));
            log.info("Google Geocoded {},{} -> {}, {}, {}", lat, lon, city, region, country);
            return true;
        } catch (Exception e) {
            log.warn("Google Geocoding failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean containsType(JsonNode typesArray, String type) {
        if (!typesArray.isArray()) return false;
        for (JsonNode t : typesArray) {
            if (type.equals(t.asText(""))) return true;
        }
        return false;
    }

    private static boolean tryNominatimGeocode(double lat, double lon, Map<String, Object> result) {
        try {
            String url = String.format(
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%f&lon=%f&zoom=14&addressdetails=1",
                    lat, lon);
            String response = httpGet(url, "ActivePulse/1.0 (contact: support@aress.com)");
            if (response == null || response.isBlank()) return false;

            JsonNode root = mapper.readTree(response);
            JsonNode address = root.path("address");
            if (address.isMissingNode()) return false;

            String city = firstNonEmpty(
                    address.path("city").asText(""),
                    address.path("town").asText(""),
                    address.path("village").asText(""),
                    address.path("municipality").asText(""),
                    address.path("suburb").asText(""),
                    address.path("county").asText(""));
            if (city.isBlank()) return false;

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

    // ─── Google Geolocation API ──────────────────────────────────────

    private static double[] getCoordinatesFromGoogleGeolocation() {
        String apiKey = EnvConfig.get("GOOGLE_GEOCODING_API_KEY", "").trim();
        if (apiKey.isBlank()) {
            log.debug("No Google API key; skipping Geolocation API");
            return null;
        }
        try {
            String url = "https://www.googleapis.com/geolocation/v1/geolocate?key="
                    + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            String body = "{\"considerIp\":true}";
            String response = httpPost(url, body, "application/json");
            if (response == null || response.isBlank()) return null;

            JsonNode root = mapper.readTree(response);
            JsonNode locNode = root.path("location");
            if (locNode.isMissingNode()) {
                log.warn("Google Geolocation missing 'location': {}", response);
                return null;
            }
            double lat = locNode.path("lat").asDouble(0.0);
            double lng = locNode.path("lng").asDouble(0.0);
            if (lat == 0.0 && lng == 0.0) return null;
            log.info("Google Geolocation accuracy: {}m", root.path("accuracy").asDouble(-1));
            return new double[]{lat, lng};
        } catch (Exception e) {
            log.warn("Google Geolocation failed: {}", e.getMessage());
            return null;
        }
    }

    // ─── IP-based fallback ───────────────────────────────────────────

    private static void tryIpFallback(Map<String, Object> result, boolean overwriteCoords) {
        if (tryIpGeolocationPage(result, overwriteCoords)) return;
        log.warn("IPGeolocation.io unavailable; using ip-api.com fallback");
        tryIpApiFallback(result, overwriteCoords);
    }

    private static boolean tryIpGeolocationPage(Map<String, Object> result, boolean overwriteCoords) {
        try {
            String publicIp = httpGet("https://api.ipify.org", IPGEOLOCATION_USER_AGENT);
            if (publicIp == null || publicIp.isBlank()) return false;
            publicIp = publicIp.trim();

            String pageUrl = "https://ipgeolocation.io/what-is-my-ip/"
                    + URLEncoder.encode(publicIp, StandardCharsets.UTF_8);
            String html = httpGet(pageUrl, IPGEOLOCATION_USER_AGENT);
            if (html == null || html.isBlank()) return false;

            Matcher matcher = IPGEOLOCATION_DATA_PATTERN.matcher(html);
            if (!matcher.find()) return false;

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
            }

            log.info("IPGeolocation.io resolved {} → {}, {}, {} (overwriteCoords={})",
                    result.get("publicIp"), city, region, country, overwriteCoords);
            return true;
        } catch (Exception e) {
            log.warn("IPGeolocation.io lookup failed: {}", e.getMessage());
            return false;
        }
    }

    private static void tryIpApiFallback(Map<String, Object> result, boolean overwriteCoords) {
        try {
            String response = httpGet(
                    "http://ip-api.com/json/?fields=status,message,country,"
                            + "region,regionName,city,zip,lat,lon,query", null);
            if (response == null || response.isBlank()) return;

            JsonNode node = mapper.readTree(response);
            if (!"success".equals(node.path("status").asText())) return;

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

    // ─── HTTP helpers ────────────────────────────────────────────────

    private static String httpGet(String urlString, String userAgent) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(urlString).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
            if (conn.getResponseCode() != 200) return null;
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

    private static String httpPost(String urlString, String body, String contentType) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(urlString).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("User-Agent", "ActivePulse/1.0");
            conn.setDoOutput(true);

            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", Integer.toString(data.length));
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }

            int code = conn.getResponseCode();
            java.io.InputStream stream = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) return null;

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                if (code != 200) {
                    log.debug("HTTP {} from {}: {}", code, urlString, sb.toString());
                    return null;
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.debug("HTTP POST {} failed: {}", urlString, e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

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

// package com.activepulse.agent.util;

// import com.fasterxml.jackson.databind.JsonNode;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import java.io.BufferedReader;
// import java.io.InputStreamReader;
// import java.net.HttpURLConnection;
// import java.net.InetAddress;
// import java.net.NetworkInterface;
// import java.net.URI;
// import java.net.URL;
// import java.net.URLEncoder;
// import java.nio.charset.StandardCharsets;
// import java.time.Duration;
// import java.time.Instant;
// import java.util.Collections;
// import java.util.Enumeration;
// import java.util.LinkedHashMap;
// import java.util.Map;
// import java.util.regex.Matcher;
// import java.util.regex.Pattern;
// import java.util.stream.Collectors;

// /**
//  * Builds the nested `location` block sent in each sync payload.
//  *
//  * CHANGES IN THIS VERSION:
//  *  - REMOVED PowerShell GeoCoordinateWatcher entirely (AVG was blocking it,
//  *    and even when permitted, it was unreliable across restarts).
//  *  - Added MULTI-OFFICE subnet override:
//  *      * 192.168.30.x → Pune (Aress main office)
//  *      * 192.168.210.x, 192.168.8.x, 192.168.9.x, 192.168.137.x → Nashik
//  *      * Anything else → falls through to Google Geolocation / IP fallback
//  *  - Office override happens FIRST (before any geocoding), eliminating
//  *    the wrong-Mumbai problem that IP geolocation causes due to Jio routing.
//  *  - Office subnets/coords are configurable via agent.env (see keys below).
//  *
//  * agent.env keys (with defaults):
//  *   OFFICE_PUNE_SUBNETS         = 192.168.30
//  *   OFFICE_PUNE_LAT             = 18.511638
//  *   OFFICE_PUNE_LNG             = 73.925636
//  *   OFFICE_NASHIK_SUBNETS       = 192.168.210,192.168.8,192.168.9,192.168.137
//  *   OFFICE_NASHIK_LAT           = 19.9975
//  *   OFFICE_NASHIK_LNG           = 73.7898
//  *   GOOGLE_GEOCODING_API_KEY    = (optional; enables Google Geolocation for WFH users)
//  *
//  * Subnet matching is done by PREFIX (e.g. "192.168.30") not CIDR — simpler and
//  * sufficient for /24 subnets. Just list the first 3 octets, comma-separated.
//  *
//  * Output format:
//  *   {
//  *     "address":   "Pune, Maharashtra, India",
//  *     "latitude":  18.511638,
//  *     "longitude": 73.925636,
//  *     "city":      "Pune",
//  *     "region":    "Maharashtra",
//  *     "country":   "India",
//  *     "zip":       "",
//  *     "publicIp":  "114.143.178.130",
//  *     "privateIp": "192.168.30.116"
//  *   }
//  */
// public final class MachineInfo {

//     private static final Logger log = LoggerFactory.getLogger(MachineInfo.class);
//     private static final ObjectMapper mapper = new ObjectMapper();

//     private static final int HTTP_TIMEOUT_MS = 5_000;
//     private static final String IPGEOLOCATION_USER_AGENT = "ActivePulse/1.0";
//     private static final Pattern IPGEOLOCATION_DATA_PATTERN = Pattern.compile(
//             "id=\"code-json\"[^>]*data-full=\"(.*?)\"", Pattern.DOTALL);

//     private static final Duration CACHE_TTL_FRESH = Duration.ofMinutes(30);
//     private static final Duration CACHE_TTL_STALE = Duration.ofMinutes(5);

//     private static volatile Map<String, Object> cachedLocation;
//     private static volatile Instant cachedAt;

//     private MachineInfo() {}

//     // ─── Public API ──────────────────────────────────────────────────

//     public static Map<String, Object> getLocationPayload() {
//         Instant now = Instant.now();
//         if (cachedLocation != null && cachedAt != null) {
//             Duration age = Duration.between(cachedAt, now);
//             boolean isComplete = !((String) cachedLocation.getOrDefault("city", "")).isBlank();
//             Duration ttl = isComplete ? CACHE_TTL_FRESH : CACHE_TTL_STALE;
//             if (age.compareTo(ttl) < 0) return cachedLocation;
//         }
//         Map<String, Object> result = buildLocationPayload();
//         cachedLocation = result;
//         cachedAt = now;
//         return result;
//     }

//     @Deprecated
//     public static Map<String, Object> getSyncDetails() {
//         Map<String, Object> loc = getLocationPayload();
//         Map<String, Object> out = new LinkedHashMap<>();
//         out.put("privateIp",       loc.get("privateIp"));
//         out.put("publicIp",        loc.get("publicIp"));
//         out.put("locationDetails",
//                 loc.get("city") + ", " + loc.get("region") + ", " + loc.get("country"));
//         return out;
//     }

//     // ─── Main flow ───────────────────────────────────────────────────

//     private static Map<String, Object> buildLocationPayload() {
//         Map<String, Object> result = new LinkedHashMap<>();
//         result.put("address",   "");
//         result.put("latitude",  0.0);
//         result.put("longitude", 0.0);
//         result.put("city",      "");
//         result.put("region",    "");
//         result.put("country",   "");
//         result.put("zip",       "");
//         result.put("publicIp",  "");
//         result.put("privateIp", getPrivateIp());

//         // 1) OFFICE OVERRIDE — if private IP matches a known office subnet, force those coords.
//         //    Runs FIRST so we never have to wait for slow/wrong geocoding when at office.
//         boolean isOffice = applyOfficeOverride(result);

//         // 2) For non-office users (WFH), try Google Geolocation API for accurate coords.
//         //    Skipped if already at office (we have authoritative coords).
//         if (!isOffice) {
//             double[] preciseCoords = getCoordinatesFromGoogleGeolocation();
//             if (preciseCoords != null) {
//                 result.put("latitude",  preciseCoords[0]);
//                 result.put("longitude", preciseCoords[1]);
//                 log.info("Google Geolocation -> {}, {}", preciseCoords[0], preciseCoords[1]);
//                 tryReverseGeocode(preciseCoords[0], preciseCoords[1], result);
//             }
//         }

//         // 3) IP fallback ALWAYS runs to record publicIp.
//         //    Only overwrites city/coords if we don't already have them.
//         boolean cityBlank = ((String) result.get("city")).isBlank();
//         tryIpFallback(result, cityBlank && !isOffice);

//         return result;
//     }

//     // ─── OFFICE SUBNET OVERRIDE ──────────────────────────────────────

//     /**
//      * Checks the private IP against known office subnets and overrides
//      * location with authoritative office coordinates if matched.
//      *
//      * Subnet matching uses PREFIX comparison on first 3 octets.
//      * Examples: "192.168.30" matches "192.168.30.116"
//      *           "192.168.210" matches "192.168.210.148"
//      *
//      * @return true if private IP matched a known office, false otherwise
//      */
//     private static boolean applyOfficeOverride(Map<String, Object> result) {
//         String privateIp = (String) result.get("privateIp");
//         if (privateIp == null || privateIp.isBlank()) return false;

//         // PUNE — Aress main office
//         String puneSubnets = EnvConfig.get("OFFICE_PUNE_SUBNETS", "192.168.30").trim();
//         if (matchesAnySubnet(privateIp, puneSubnets)) {
//             result.put("city",      "Pune");
//             result.put("region",    "Maharashtra");
//             result.put("country",   "India");
//             result.put("address",   "Pune, Maharashtra, India");
//             result.put("latitude",  EnvConfig.getDouble("OFFICE_PUNE_LAT", 18.511638));
//             result.put("longitude", EnvConfig.getDouble("OFFICE_PUNE_LNG", 73.925636));
//             log.info("Office subnet match: {} → Pune (forced)", privateIp);
//             return true;
//         }

//         // NASHIK — multiple subnets
//         String nashikSubnets = EnvConfig.get(
//                 "OFFICE_NASHIK_SUBNETS",
//                 "192.168.210,192.168.8,192.168.9,192.168.137").trim();
//         if (matchesAnySubnet(privateIp, nashikSubnets)) {
//             result.put("city",      "Nashik");
//             result.put("region",    "Maharashtra");
//             result.put("country",   "India");
//             result.put("address",   "Nashik, Maharashtra, India");
//             result.put("latitude",  EnvConfig.getDouble("OFFICE_NASHIK_LAT", 19.9975));
//             result.put("longitude", EnvConfig.getDouble("OFFICE_NASHIK_LNG", 73.7898));
//             log.info("Office subnet match: {} → Nashik (forced)", privateIp);
//             return true;
//         }

//         log.debug("Private IP {} did not match any known office subnet", privateIp);
//         return false;
//     }

//     /**
//      * Returns true if {@code privateIp} starts with any of the comma-separated
//      * subnet prefixes in {@code subnetsCsv}.
//      *
//      * Each prefix is matched as "prefix." (with trailing dot) to avoid
//      * "192.168.3" wrongly matching "192.168.30.x".
//      */
//     private static boolean matchesAnySubnet(String privateIp, String subnetsCsv) {
//         if (subnetsCsv == null || subnetsCsv.isBlank()) return false;
//         for (String prefix : subnetsCsv.split(",")) {
//             String p = prefix.trim();
//             if (p.isEmpty()) continue;
//             // Ensure trailing dot to prevent "192.168.3" matching "192.168.30.x"
//             if (!p.endsWith(".")) p = p + ".";
//             if (privateIp.startsWith(p)) return true;
//         }
//         return false;
//     }

//     // ─── Reverse geocoding ───────────────────────────────────────────

//     private static boolean tryReverseGeocode(double lat, double lon, Map<String, Object> result) {
//         String apiKey = EnvConfig.get("GOOGLE_GEOCODING_API_KEY", "").trim();
//         if (!apiKey.isBlank()) {
//             if (tryGoogleGeocode(lat, lon, apiKey, result)) return true;
//             log.warn("Google Geocoding failed -- falling back to Nominatim");
//         }
//         return tryNominatimGeocode(lat, lon, result);
//     }

//     private static boolean tryGoogleGeocode(double lat, double lon, String apiKey,
//                                              Map<String, Object> result) {
//         try {
//             String url = String.format(
//                     "https://maps.googleapis.com/maps/api/geocode/json?latlng=%f,%f&key=%s",
//                     lat, lon, URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
//             String response = httpGet(url, "ActivePulse/1.0");
//             if (response == null || response.isBlank()) return false;

//             JsonNode root = mapper.readTree(response);
//             if (!"OK".equals(root.path("status").asText(""))) {
//                 log.warn("Google Geocoding status={}", root.path("status").asText(""));
//                 return false;
//             }

//             JsonNode results = root.path("results");
//             if (!results.isArray() || results.isEmpty()) return false;

//             JsonNode best = results.get(0);
//             for (JsonNode candidate : results) {
//                 if (containsType(candidate.path("types"), "locality")) {
//                     best = candidate;
//                     break;
//                 }
//             }

//             String city = "", region = "", country = "", postalCode = "";
//             JsonNode components = best.path("address_components");
//             if (components.isArray()) {
//                 for (JsonNode component : components) {
//                     JsonNode types = component.path("types");
//                     String longName = component.path("long_name").asText("");
//                     if (containsType(types, "locality") || containsType(types, "postal_town")) {
//                         city = longName;
//                     } else if (containsType(types, "administrative_area_level_1")) {
//                         region = longName;
//                     } else if (containsType(types, "country")) {
//                         country = longName;
//                     } else if (containsType(types, "postal_code")) {
//                         postalCode = longName;
//                     }
//                 }
//             }

//             if (city.isBlank() && components.isArray()) {
//                 for (JsonNode component : components) {
//                     JsonNode types = component.path("types");
//                     if (containsType(types, "sublocality") ||
//                         containsType(types, "administrative_area_level_2")) {
//                         city = component.path("long_name").asText("");
//                         if (!city.isBlank()) break;
//                     }
//                 }
//             }

//             if (city.isBlank()) return false;

//             result.put("city",    city);
//             result.put("region",  region);
//             result.put("country", country);
//             result.put("zip",     postalCode);
//             result.put("address", best.path("formatted_address").asText(city));
//             log.info("Google Geocoded {},{} -> {}, {}, {}", lat, lon, city, region, country);
//             return true;
//         } catch (Exception e) {
//             log.warn("Google Geocoding failed: {}", e.getMessage());
//             return false;
//         }
//     }

//     private static boolean containsType(JsonNode typesArray, String type) {
//         if (!typesArray.isArray()) return false;
//         for (JsonNode t : typesArray) {
//             if (type.equals(t.asText(""))) return true;
//         }
//         return false;
//     }

//     private static boolean tryNominatimGeocode(double lat, double lon, Map<String, Object> result) {
//         try {
//             String url = String.format(
//                     "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%f&lon=%f&zoom=14&addressdetails=1",
//                     lat, lon);
//             String response = httpGet(url, "ActivePulse/1.0 (contact: support@aress.com)");
//             if (response == null || response.isBlank()) return false;

//             JsonNode root = mapper.readTree(response);
//             JsonNode address = root.path("address");
//             if (address.isMissingNode()) return false;

//             String city = firstNonEmpty(
//                     address.path("city").asText(""),
//                     address.path("town").asText(""),
//                     address.path("village").asText(""),
//                     address.path("municipality").asText(""),
//                     address.path("suburb").asText(""),
//                     address.path("county").asText(""));
//             if (city.isBlank()) return false;

//             result.put("city",    city);
//             result.put("region",  address.path("state").asText(""));
//             result.put("country", address.path("country").asText(""));
//             result.put("zip",     address.path("postcode").asText(""));
//             result.put("address", root.path("display_name").asText(city));
//             log.info("Nominatim geocoded {},{} -> {}, {}, {}",
//                     lat, lon, city, result.get("region"), result.get("country"));
//             return true;
//         } catch (Exception e) {
//             log.warn("Nominatim geocode failed: {}", e.getMessage());
//             return false;
//         }
//     }

//     // ─── Google Geolocation API (precise coords, no PowerShell) ──────

//     private static double[] getCoordinatesFromGoogleGeolocation() {
//         String apiKey = EnvConfig.get("GOOGLE_GEOCODING_API_KEY", "").trim();
//         if (apiKey.isBlank()) {
//             log.debug("No Google API key; skipping Geolocation API");
//             return null;
//         }
//         try {
//             String url = "https://www.googleapis.com/geolocation/v1/geolocate?key="
//                     + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
//             String body = "{\"considerIp\":true}";
//             String response = httpPost(url, body, "application/json");
//             if (response == null || response.isBlank()) return null;

//             JsonNode root = mapper.readTree(response);
//             JsonNode locNode = root.path("location");
//             if (locNode.isMissingNode()) {
//                 log.warn("Google Geolocation missing 'location': {}", response);
//                 return null;
//             }
//             double lat = locNode.path("lat").asDouble(0.0);
//             double lng = locNode.path("lng").asDouble(0.0);
//             if (lat == 0.0 && lng == 0.0) return null;
//             log.info("Google Geolocation accuracy: {}m", root.path("accuracy").asDouble(-1));
//             return new double[]{lat, lng};
//         } catch (Exception e) {
//             log.warn("Google Geolocation failed: {}", e.getMessage());
//             return null;
//         }
//     }

//     // ─── IP-based fallback ───────────────────────────────────────────

//     private static void tryIpFallback(Map<String, Object> result, boolean overwriteCoords) {
//         if (tryIpGeolocationPage(result, overwriteCoords)) return;
//         log.warn("IPGeolocation.io unavailable; using ip-api.com fallback");
//         tryIpApiFallback(result, overwriteCoords);
//     }

//     private static boolean tryIpGeolocationPage(Map<String, Object> result, boolean overwriteCoords) {
//         try {
//             String publicIp = httpGet("https://api.ipify.org", IPGEOLOCATION_USER_AGENT);
//             if (publicIp == null || publicIp.isBlank()) return false;
//             publicIp = publicIp.trim();

//             String pageUrl = "https://ipgeolocation.io/what-is-my-ip/"
//                     + URLEncoder.encode(publicIp, StandardCharsets.UTF_8);
//             String html = httpGet(pageUrl, IPGEOLOCATION_USER_AGENT);
//             if (html == null || html.isBlank()) return false;

//             Matcher matcher = IPGEOLOCATION_DATA_PATTERN.matcher(html);
//             if (!matcher.find()) return false;

//             JsonNode node = mapper.readTree(decodeHtmlAttribute(matcher.group(1)));
//             JsonNode location = node.path("location");
//             if (location.isMissingNode() || location.isNull()) return false;

//             result.put("publicIp", node.path("ip").asText(publicIp));

//             String city = location.path("city").asText("");
//             String region = location.path("state_prov").asText("");
//             String country = location.path("country_name").asText("");

//             if (overwriteCoords) {
//                 result.put("city",      city);
//                 result.put("region",    region);
//                 result.put("country",   country);
//                 result.put("zip",       location.path("zipcode").asText(""));
//                 result.put("address",   buildAddress(city, region, country));
//                 result.put("latitude",  location.path("latitude").asDouble(0.0));
//                 result.put("longitude", location.path("longitude").asDouble(0.0));
//             }

//             log.info("IPGeolocation.io resolved {} → {}, {}, {} (overwriteCoords={})",
//                     result.get("publicIp"), city, region, country, overwriteCoords);
//             return true;
//         } catch (Exception e) {
//             log.warn("IPGeolocation.io lookup failed: {}", e.getMessage());
//             return false;
//         }
//     }

//     private static void tryIpApiFallback(Map<String, Object> result, boolean overwriteCoords) {
//         try {
//             String response = httpGet(
//                     "http://ip-api.com/json/?fields=status,message,country,"
//                             + "region,regionName,city,zip,lat,lon,query", null);
//             if (response == null || response.isBlank()) return;

//             JsonNode node = mapper.readTree(response);
//             if (!"success".equals(node.path("status").asText())) return;

//             result.put("publicIp", node.path("query").asText(""));

//             if (overwriteCoords) {
//                 String city = node.path("city").asText("");
//                 result.put("city",      city);
//                 result.put("region",    node.path("regionName").asText(""));
//                 result.put("country",   node.path("country").asText(""));
//                 result.put("zip",       node.path("zip").asText(""));
//                 result.put("address",   city);
//                 result.put("latitude",  node.path("lat").asDouble(0.0));
//                 result.put("longitude", node.path("lon").asDouble(0.0));
//             }
//         } catch (Exception e) {
//             log.warn("IP geolocation failed: {}", e.getMessage());
//         }
//     }

//     // ─── Private IP discovery ────────────────────────────────────────

//     public static String getPrivateIp() {
//         try {
//             Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
//             if (ifaces != null) {
//                 for (NetworkInterface ni : Collections.list(ifaces)) {
//                     if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
//                     for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
//                         if (addr.isSiteLocalAddress()
//                                 && !addr.isLoopbackAddress()
//                                 && addr.getHostAddress().indexOf(':') < 0) {
//                             return addr.getHostAddress();
//                         }
//                     }
//                 }
//             }
//             return InetAddress.getLocalHost().getHostAddress();
//         } catch (Exception e) {
//             log.debug("getPrivateIp failed: {}", e.getMessage());
//             return "";
//         }
//     }

//     // ─── HTTP helpers ────────────────────────────────────────────────

//     private static String httpGet(String urlString, String userAgent) {
//         HttpURLConnection conn = null;
//         try {
//             URL url = URI.create(urlString).toURL();
//             conn = (HttpURLConnection) url.openConnection();
//             conn.setRequestMethod("GET");
//             conn.setConnectTimeout(HTTP_TIMEOUT_MS);
//             conn.setReadTimeout(HTTP_TIMEOUT_MS);
//             if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
//             if (conn.getResponseCode() != 200) return null;
//             try (BufferedReader r = new BufferedReader(
//                     new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
//                 StringBuilder sb = new StringBuilder();
//                 String line;
//                 while ((line = r.readLine()) != null) sb.append(line);
//                 return sb.toString();
//             }
//         } catch (Exception e) {
//             log.debug("HTTP GET {} failed: {}", urlString, e.getMessage());
//             return null;
//         } finally {
//             if (conn != null) conn.disconnect();
//         }
//     }

//     private static String httpPost(String urlString, String body, String contentType) {
//         HttpURLConnection conn = null;
//         try {
//             URL url = URI.create(urlString).toURL();
//             conn = (HttpURLConnection) url.openConnection();
//             conn.setRequestMethod("POST");
//             conn.setConnectTimeout(HTTP_TIMEOUT_MS);
//             conn.setReadTimeout(HTTP_TIMEOUT_MS);
//             conn.setRequestProperty("Content-Type", contentType);
//             conn.setRequestProperty("User-Agent", "ActivePulse/1.0");
//             conn.setDoOutput(true);

//             byte[] data = body.getBytes(StandardCharsets.UTF_8);
//             conn.setRequestProperty("Content-Length", Integer.toString(data.length));
//             try (java.io.OutputStream os = conn.getOutputStream()) {
//                 os.write(data);
//             }

//             int code = conn.getResponseCode();
//             java.io.InputStream stream = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
//             if (stream == null) return null;

//             try (BufferedReader r = new BufferedReader(
//                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
//                 StringBuilder sb = new StringBuilder();
//                 String line;
//                 while ((line = r.readLine()) != null) sb.append(line);
//                 if (code != 200) {
//                     log.debug("HTTP {} from {}: {}", code, urlString, sb.toString());
//                     return null;
//                 }
//                 return sb.toString();
//             }
//         } catch (Exception e) {
//             log.debug("HTTP POST {} failed: {}", urlString, e.getMessage());
//             return null;
//         } finally {
//             if (conn != null) conn.disconnect();
//         }
//     }

//     // ─── Misc helpers ────────────────────────────────────────────────

//     private static String firstNonEmpty(String... values) {
//         for (String v : values) if (v != null && !v.isBlank()) return v;
//         return "";
//     }

//     private static String buildAddress(String city, String region, String country) {
//         return java.util.stream.Stream.of(city, region, country)
//                 .filter(value -> value != null && !value.isBlank())
//                 .collect(Collectors.joining(", "));
//     }

//     private static String decodeHtmlAttribute(String value) {
//         return value
//                 .replace("&quot;", "\"")
//                 .replace("&#39;", "'")
//                 .replace("&lt;", "<")
//                 .replace("&gt;", ">")
//                 .replace("&amp;", "&");
//     }
// }


