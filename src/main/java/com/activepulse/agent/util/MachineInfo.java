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
 * v1.0.8 CHANGES:
 *  - matchesAnySubnet() now does REAL CIDR matching (e.g. 10.179.0.0/16),
 *    while still accepting bare octet prefixes ("192.168.30") for back-compat.
 *  - IP fallback restored to two providers: ip-api.com (JSON, primary) then
 *    ipgeolocation.io (HTML scrape, secondary). Fixes blank 0.0/0.0 result
 *    when a WFH machine's subnet does not match any office.
 *  - Last-known-good cache: if every live method fails this cycle, reuse the
 *    previous resolved location instead of emitting blank/zero coordinates.
 *  - locationSource tag added to the payload so the portal can tell an exact
 *    office fix from an approximate IP fix:
 *      OFFICE_EXACT | GEO_APPROX | IP_APPROX | LAST_KNOWN | UNRESOLVED
 *
 * Subnet -> office mapping (defaults; override in agent.env):
 *   PUNE:    192.168.30
 *   NASHIK:  192.168.210, 192.168.137, 192.168.8, 192.168.9
 *   EXTRA:   192.168.70, 192.168.60
 *
 * Output format:
 *   {
 *     "address":        "Pune, Maharashtra, India",
 *     "latitude":       18.511033,
 *     "longitude":      73.925595,
 *     "city":           "Pune",
 *     "region":         "Maharashtra",
 *     "country":        "India",
 *     "zip":            "",
 *     "publicIp":       "114.143.178.130",
 *     "privateIp":      "192.168.30.116",
 *     "locationSource": "OFFICE_EXACT"
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

    // Last successfully-resolved location (non-blank city). Survives across
    // cycles so a fully-failed lookup can reuse it instead of returning blanks.
    private static volatile Map<String, Object> lastGoodLocation;

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
        result.put("address",        "");
        result.put("latitude",       0.0);
        result.put("longitude",      0.0);
        result.put("city",           "");
        result.put("region",         "");
        result.put("country",        "");
        result.put("zip",            "");
        result.put("publicIp",       "");
        result.put("privateIp",      getPrivateIp());
        result.put("locationSource", "UNRESOLVED");

        // 1) OFFICE OVERRIDE — exact, guaranteed, no network/permission needed.
        if (applyOfficeOverride(result)) {
            result.put("locationSource", "OFFICE_EXACT");
            lastGoodLocation = new LinkedHashMap<>(result);
            return result;
        }

        // 2) WFH: optional Google Geolocation (only if API key configured).
        double[] preciseCoords = getCoordinatesFromGoogleGeolocation();
        if (preciseCoords != null) {
            result.put("latitude",  preciseCoords[0]);
            result.put("longitude", preciseCoords[1]);
            log.info("Google Geolocation -> {}, {}", preciseCoords[0], preciseCoords[1]);
            if (tryReverseGeocode(preciseCoords[0], preciseCoords[1], result)) {
                result.put("locationSource", "GEO_APPROX");
            }
        }

        // 3) IP fallback: ALWAYS records publicIp; fills city/coords if blank.
        boolean cityBlank = ((String) result.get("city")).isBlank();
        tryIpFallback(result, cityBlank);
        if ("UNRESOLVED".equals(result.get("locationSource"))
                && !((String) result.get("city")).isBlank()) {
            result.put("locationSource", "IP_APPROX");
        }

        // 4) Everything failed this cycle -> reuse last known good so we never
        //    emit blank city / 0.0 coordinates. Location is stable between the
        //    5-minute syncs, so the previous fix is the best available answer.
        if (((String) result.get("city")).isBlank() && lastGoodLocation != null) {
            Map<String, Object> reused = new LinkedHashMap<>(lastGoodLocation);
            // Keep THIS cycle's freshly captured IPs if we have them.
            String freshPublic  = (String) result.get("publicIp");
            String freshPrivate = (String) result.get("privateIp");
            if (freshPublic  != null && !freshPublic.isBlank())  reused.put("publicIp",  freshPublic);
            if (freshPrivate != null && !freshPrivate.isBlank()) reused.put("privateIp", freshPrivate);
            reused.put("locationSource", "LAST_KNOWN");
            log.warn("Location unresolved this cycle; reusing last known good ({})",
                    reused.get("city"));
            return reused;
        }

        // Remember a fresh, real resolution for future fallback.
        if (!((String) result.get("city")).isBlank()) {
            lastGoodLocation = new LinkedHashMap<>(result);
        } else {
            log.warn("Location fully unresolved this cycle and no last-known-good available");
        }

        return result;
    }

    // ─── OFFICE SUBNET OVERRIDE ──────────────────────────────────────

    /**
     * Checks the private IP against known office subnets and overrides
     * location with authoritative office coordinates if matched.
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
            log.info("Office subnet match: {} -> Pune (forced)", privateIp);
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
            log.info("Office subnet match: {} -> Nashik (forced)", privateIp);
            return true;
        }

        // EXTRA — for unknown-city subnets. Defaults to Pune coords; reassign
        // via agent.env if these belong to a different office.
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
            log.info("Office subnet match: {} -> {} (extra, forced)", privateIp, city);
            return true;
        }

        log.debug("Private IP {} did not match any known office subnet", privateIp);
        return false;
    }

    /**
     * True if {@code ip} falls inside any of the comma-separated entries.
     *
     * Each entry may be:
     *   "10.179.0.0/16"   real CIDR (any prefix length 0-32)
     *   "192.168.30.0/24" real CIDR
     *   "192.168.30"      bare octet prefix (matches 192.168.30.*)  [back-compat]
     *   "192.168.30.15"   full IP (exact /32 match)
     *
     * Malformed entries are skipped, not fatal.
     */
    private static boolean matchesAnySubnet(String ip, String subnetsCsv) {
        if (ip == null || ip.isBlank() || subnetsCsv == null || subnetsCsv.isBlank()) {
            return false;
        }
        long ipLong;
        try {
            ipLong = ipToLong(ip);
        } catch (Exception e) {
            return false; // not a parseable IPv4 (e.g. IPv6) — cannot match
        }

        for (String raw : subnetsCsv.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            try {
                if (entry.contains("/")) {
                    // Real CIDR: network/bits
                    String[] parts = entry.split("/");
                    int bits = Integer.parseInt(parts[1].trim());
                    if (bits < 0 || bits > 32) continue;
                    long net  = ipToLong(padToFullIp(parts[0].trim()));
                    long mask = (bits == 0) ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
                    if ((ipLong & mask) == (net & mask)) return true;
                } else {
                    int octetCount = entry.split("\\.").length;
                    if (octetCount == 4) {
                        // Full IP -> exact match
                        if (ipToLong(entry) == ipLong) return true;
                    } else {
                        // Bare prefix ("192.168.30") -> leading-octet prefix match.
                        String p = entry.endsWith(".") ? entry : entry + ".";
                        if (ip.startsWith(p)) return true;
                    }
                }
            } catch (Exception ignore) {
                log.debug("Skipping malformed subnet entry: '{}'", entry);
            }
        }
        return false;
    }

    /** Convert dotted IPv4 to an unsigned 32-bit value held in a long. */
    private static long ipToLong(String ip) {
        String[] o = ip.trim().split("\\.");
        if (o.length != 4) throw new IllegalArgumentException("not IPv4: " + ip);
        long v = 0;
        for (int i = 0; i < 4; i++) {
            int part = Integer.parseInt(o[i].trim());
            if (part < 0 || part > 255) throw new IllegalArgumentException("bad octet: " + ip);
            v = (v << 8) | part;
        }
        return v & 0xFFFFFFFFL;
    }

    /** "10.179" -> "10.179.0.0"; "10.179.0.0" stays. Used for CIDR network part. */
    private static String padToFullIp(String maybePartial) {
        String[] o = maybePartial.trim().split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append('.');
            sb.append(i < o.length && !o[i].isBlank() ? o[i].trim() : "0");
        }
        return sb.toString();
    }

    // ─── Reverse geocoding ───────────────────────────────────────────

    private static boolean tryReverseGeocode(double lat, double lon,
                                             Map<String, Object> result) {
        String apiKey = EnvConfig.get("GOOGLE_GEOCODING_API_KEY", "").trim();
        if (apiKey.isBlank()) return false;
        return tryGoogleGeocode(lat, lon, apiKey, result);
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

    /**
     * Two-provider IP fallback:
     *   1. ip-api.com        (single JSON GET, no key, no scraping) — PRIMARY
     *   2. ipgeolocation.io  (HTML scrape; fragile) — SECONDARY
     * Always records publicIp; only overwrites city/coords when overwriteCoords.
     */
    private static void tryIpFallback(Map<String, Object> result, boolean overwriteCoords) {
        if (tryIpApiFallback(result, overwriteCoords)) return;
        log.warn("ip-api.com unavailable; trying ipgeolocation.io");
        if (tryIpGeolocationPage(result, overwriteCoords)) return;
        log.warn("All IP geolocation providers failed; location left blank this cycle.");
    }

    /** ip-api.com — reliable JSON, no API key, no HTML scraping. */
    private static boolean tryIpApiFallback(Map<String, Object> result, boolean overwriteCoords) {
        try {
            String response = httpGet(
                    "http://ip-api.com/json/?fields=status,message,country,"
                            + "regionName,city,zip,lat,lon,query", null);
            if (response == null || response.isBlank()) return false;

            JsonNode node = mapper.readTree(response);
            if (!"success".equals(node.path("status").asText())) {
                log.debug("ip-api.com status={}", node.path("message").asText(""));
                return false;
            }

            result.put("publicIp", node.path("query").asText(""));

            if (overwriteCoords) {
                String city    = node.path("city").asText("");
                String region  = node.path("regionName").asText("");
                String country = node.path("country").asText("");
                result.put("city",      city);
                result.put("region",    region);
                result.put("country",   country);
                result.put("zip",       node.path("zip").asText(""));
                result.put("address",   buildAddress(city, region, country));
                result.put("latitude",  node.path("lat").asDouble(0.0));
                result.put("longitude", node.path("lon").asDouble(0.0));
            }

            log.info("ip-api.com resolved {} -> {}, {}, {} ({},{}) overwriteCoords={}",
                    result.get("publicIp"),
                    node.path("city").asText(""), node.path("regionName").asText(""),
                    node.path("country").asText(""),
                    node.path("lat").asDouble(0.0), node.path("lon").asDouble(0.0),
                    overwriteCoords);
            return true;
        } catch (Exception e) {
            log.warn("ip-api.com lookup failed: {}", e.getMessage());
            return false;
        }
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

            log.info("ipgeolocation.io resolved {} -> {}, {}, {} (overwriteCoords={})",
                    result.get("publicIp"), city, region, country, overwriteCoords);
            return true;
        } catch (Exception e) {
            log.warn("ipgeolocation.io lookup failed: {}", e.getMessage());
            return false;
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