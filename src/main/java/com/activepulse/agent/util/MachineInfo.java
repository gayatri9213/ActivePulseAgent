package com.activepulse.agent.util;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the nested `location` block sent in each sync payload.
 *
 * SCOPE
 * -----
 * This class does WFH/WFO classification ONLY.
 *
 * Device coordinates are never collected. There is no Windows
 * Location call and no reverse geocoding. A WFH machine reports
 * null for every location field; only the IPs and locationSource
 * are populated.
 *
 * OFFICE SITE MODEL
 * -----------------
 * Every office is a "site" with its own signals and coordinates:
 *
 *   OFFICE_SITES=PUNE,NASHIK
 *
 *   OFFICE_PUNE_SUBNETS=192.168.30.0/24
 *   OFFICE_PUNE_PUBLIC_IPS=114.143.178.130
 *   OFFICE_PUNE_SSIDS=
 *   OFFICE_PUNE_CITY / _REGION / _COUNTRY / _LAT / _LNG
 *
 *   OFFICE_NASHIK_SUBNETS=...
 *   OFFICE_NASHIK_PUBLIC_IPS=...
 *   ...
 *
 * Adding a new office = add its key to OFFICE_SITES and define
 * OFFICE_<KEY>_* values. No code change required.
 *
 * DETECTION ORDER (significant)
 * -----------------------------
 *   1. Private IP matches any site subnet          -> WFO (that site)
 *   2. Public IP matches any site public IP        -> WFO (that site)
 *   3. Public IP matches legacy OFFICE_PUBLIC_IPS  -> WFO (legacy city)
 *   4. Wi-Fi SSID matches any site SSID            -> WFO (that site)
 *   5. Wi-Fi SSID matches legacy OFFICE_SSIDS      -> WFO (legacy city)
 *   6. Else                                        -> WFH (all nulls)
 *
 * Office coordinates are authoritative configured office
 * coordinates, not device coordinates.
 */
public final class MachineInfo {

    private static final Logger log =
            LoggerFactory.getLogger(MachineInfo.class);

    private static final int HTTP_TIMEOUT_MS = 5_000;

    private static final Duration CACHE_TTL =
            Duration.ofMinutes(5);

    private static volatile Map<String, Object> cachedLocation;
    private static volatile Instant cachedAt;

    private static volatile boolean sitesLogged;

    private MachineInfo() {
    }

    // -----------------------------------------------------------------
    // OFFICE SITE
    // -----------------------------------------------------------------

    /**
     * One physical office and all signals that identify it.
     */
    private static final class OfficeSite {

        private final String key;
        private final String city;
        private final String region;
        private final String country;
        private final Double latitude;
        private final Double longitude;
        private final String subnetsCsv;
        private final String publicIpsCsv;
        private final String ssidsCsv;

        private OfficeSite(
                String key,
                String city,
                String region,
                String country,
                Double latitude,
                Double longitude,
                String subnetsCsv,
                String publicIpsCsv,
                String ssidsCsv) {

            this.key = key;
            this.city = city;
            this.region = region;
            this.country = country;
            this.latitude = latitude;
            this.longitude = longitude;
            this.subnetsCsv = subnetsCsv;
            this.publicIpsCsv = publicIpsCsv;
            this.ssidsCsv = ssidsCsv;
        }
    }

    /**
     * Reads the configured office sites.
     */
    private static List<OfficeSite> officeSites() {

        String siteKeys =
                EnvConfig.get(
                        "OFFICE_SITES",
                        "PUNE,NASHIK");

        List<OfficeSite> sites =
                new ArrayList<>();

        for (String raw : siteKeys.split(",")) {

            String key =
                    raw.trim().toUpperCase();

            if (key.isBlank()) {
                continue;
            }

            sites.add(
                    buildSite(key));
        }

        if (!sitesLogged) {

            sitesLogged = true;

            for (OfficeSite site : sites) {

                log.info(
                        "Office site loaded: {} -> city={}, subnets=[{}], publicIps=[{}], ssids=[{}]",
                        site.key,
                        site.city,
                        site.subnetsCsv,
                        site.publicIpsCsv,
                        site.ssidsCsv);
            }
        }

        return sites;
    }

    private static OfficeSite buildSite(
            String key) {

        String prefix =
                "OFFICE_" + key + "_";

        double lat =
                EnvConfig.getDouble(
                        prefix + "LAT",
                        defaultLat(key));

        double lng =
                EnvConfig.getDouble(
                        prefix + "LNG",
                        defaultLng(key));

        boolean hasCoordinates =
                !(lat == 0.0 && lng == 0.0);

        return new OfficeSite(
                key,
                EnvConfig.get(
                        prefix + "CITY",
                        defaultCity(key)).trim(),
                EnvConfig.get(
                        prefix + "REGION",
                        "Maharashtra").trim(),
                EnvConfig.get(
                        prefix + "COUNTRY",
                        "India").trim(),
                hasCoordinates ? lat : null,
                hasCoordinates ? lng : null,
                EnvConfig.get(
                        prefix + "SUBNETS",
                        defaultSubnets(key)).trim(),
                EnvConfig.get(
                        prefix + "PUBLIC_IPS",
                        defaultPublicIps(key)).trim(),
                EnvConfig.get(
                        prefix + "SSIDS",
                        "").trim());
    }

    /*
     * Built-in defaults so the agent keeps working even if
     * agent.env is older than this build.
     */

    private static String defaultCity(
            String key) {

        switch (key) {
            case "PUNE":
                return "Pune";
            case "NASHIK":
                return "Nashik";
            default:
                return "";
        }
    }

    private static double defaultLat(
            String key) {

        switch (key) {
            case "PUNE":
                return 18.511033;
            case "NASHIK":
                return 19.9433;
            default:
                return 0.0;
        }
    }

    private static double defaultLng(
            String key) {

        switch (key) {
            case "PUNE":
                return 73.925595;
            case "NASHIK":
                return 73.7265;
            default:
                return 0.0;
        }
    }

    private static String defaultSubnets(
            String key) {

        switch (key) {

            case "PUNE":
                return "192.168.30.0/24";

            case "NASHIK":
                return "192.168.210.0/24,"
                        + "192.168.137.0/24,"
                        + "192.168.8.0/24,"
                        + "192.168.9.0/24,"
                        + "192.168.70.0/24,"
                        + "192.168.60.0/24";

            default:
                return "";
        }
    }

    private static String defaultPublicIps(
            String key) {

        switch (key) {

            case "PUNE":
                return "114.143.178.130";

            case "NASHIK":
                return "49.248.139.244,"
                        + "49.248.139.245,"
                        + "203.193.165.226,"
                        + "203.193.165.227,"
                        + "115.244.75.186,"
                        + "115.244.75.190";

            default:
                return "";
        }
    }

    // -----------------------------------------------------------------
    // PUBLIC LOCATION PAYLOAD
    // -----------------------------------------------------------------

    public static Map<String, Object> getLocationPayload() {

        Instant now = Instant.now();

        if (cachedLocation != null && cachedAt != null) {

            Duration age =
                    Duration.between(cachedAt, now);

            if (age.compareTo(CACHE_TTL) < 0) {
                return cachedLocation;
            }
        }

        Map<String, Object> result =
                buildLocationPayload();

        cachedLocation = result;
        cachedAt = now;

        return result;
    }

    /**
     * Deprecated compatibility method.
     */
    @Deprecated
    public static Map<String, Object> getSyncDetails() {

        Map<String, Object> loc =
                getLocationPayload();

        Map<String, Object> out =
                new LinkedHashMap<>();

        out.put(
                "privateIp",
                loc.get("privateIp"));

        out.put(
                "publicIp",
                loc.get("publicIp"));

        out.put(
                "locationDetails",
                Stream.of(
                                loc.get("city"),
                                loc.get("region"),
                                loc.get("country"))
                        .filter(value ->
                                value != null
                                        && !String.valueOf(value).isBlank())
                        .map(String::valueOf)
                        .collect(Collectors.joining(", ")));

        return out;
    }

    // -----------------------------------------------------------------
    // BUILD LOCATION
    // -----------------------------------------------------------------

    private static Map<String, Object> buildLocationPayload() {

        Map<String, Object> result =
                new LinkedHashMap<>();

        String privateIp =
                getPrivateIp();

        String publicIp =
                getPublicIp();

        /*
         * Defaults. A WFH machine keeps every one of these null.
         */
        result.put("address", null);
        result.put("latitude", null);
        result.put("longitude", null);
        result.put("accuracy", null);

        result.put("city", null);
        result.put("region", null);
        result.put("country", null);
        result.put("zip", null);

        result.put("publicIp", publicIp);
        result.put("privateIp", privateIp);

        result.put(
                "locationSource",
                "UNKNOWN");

        List<OfficeSite> sites =
                officeSites();

        // -------------------------------------------------------------
        // 1. PRIVATE OFFICE SUBNET (per site)
        // -------------------------------------------------------------

        OfficeSite subnetSite =
                findSiteByPrivateIp(
                        sites,
                        privateIp);

        if (subnetSite != null) {

            putOfficeLocation(
                    result,
                    subnetSite);

            result.put(
                    "locationSource",
                    "OFFICE_PRIVATE_SUBNET");

            log.info(
                    "Office detected from private subnet. site={}, city={}, privateIp={}, publicIp={}",
                    subnetSite.key,
                    subnetSite.city,
                    privateIp,
                    publicIp);

            return result;
        }

        // -------------------------------------------------------------
        // 2. PUBLIC OFFICE IP (per site)
        // -------------------------------------------------------------

        OfficeSite publicIpSite =
                findSiteByPublicIp(
                        sites,
                        publicIp);

        if (publicIpSite != null) {

            putOfficeLocation(
                    result,
                    publicIpSite);

            result.put(
                    "locationSource",
                    "OFFICE_PUBLIC_IP");

            log.info(
                    "Office detected from public IP. site={}, city={}, publicIp={}, privateIp={}",
                    publicIpSite.key,
                    publicIpSite.city,
                    publicIp,
                    privateIp);

            return result;
        }

        // -------------------------------------------------------------
        // 3. LEGACY GLOBAL PUBLIC IP LIST
        //
        // Used only for public IPs not assigned to any site.
        // -------------------------------------------------------------

        if (applyLegacyPublicIpOverride(
                result,
                publicIp)) {

            result.put(
                    "locationSource",
                    "OFFICE_PUBLIC_IP");

            log.info(
                    "Office detected from legacy public IP list. publicIp={}, privateIp={}",
                    publicIp,
                    privateIp);

            return result;
        }

        // -------------------------------------------------------------
        // 4. OFFICE SSID (per site, then legacy)
        // -------------------------------------------------------------

        if (anySsidConfigured(sites)) {

            String currentSsid =
                    getCurrentSsid();

            OfficeSite ssidSite =
                    findSiteBySsid(
                            sites,
                            currentSsid);

            if (ssidSite != null) {

                putOfficeLocation(
                        result,
                        ssidSite);

                result.put(
                        "locationSource",
                        "OFFICE_SSID");

                log.info(
                        "Office detected from SSID. site={}, ssid={}, publicIp={}, privateIp={}",
                        ssidSite.key,
                        currentSsid,
                        publicIp,
                        privateIp);

                return result;
            }

            if (applyLegacySsidOverride(
                    result,
                    currentSsid)) {

                result.put(
                        "locationSource",
                        "OFFICE_SSID");

                log.info(
                        "Office detected from legacy SSID list. ssid={}, publicIp={}, privateIp={}",
                        currentSsid,
                        publicIp,
                        privateIp);

                return result;
            }
        }

        // -------------------------------------------------------------
        // 5. WFH
        //
        // No office signal matched. Every location field stays null.
        // Only publicIp, privateIp and locationSource are reported.
        // -------------------------------------------------------------

        result.put(
                "locationSource",
                "WFH_NETWORK");

        log.info(
                "WFH detected. No location fields populated. publicIp={}, privateIp={}",
                publicIp,
                privateIp);

        return result;
    }

    // -----------------------------------------------------------------
    // SITE MATCHING
    // -----------------------------------------------------------------

    private static OfficeSite findSiteByPrivateIp(
            List<OfficeSite> sites,
            String privateIp) {

        if (privateIp == null
                || privateIp.isBlank()) {

            return null;
        }

        for (OfficeSite site : sites) {

            if (matchesAnySubnet(
                    privateIp,
                    site.subnetsCsv)) {

                return site;
            }
        }

        return null;
    }

    private static OfficeSite findSiteByPublicIp(
            List<OfficeSite> sites,
            String publicIp) {

        if (publicIp == null
                || publicIp.isBlank()) {

            return null;
        }

        String trimmed =
                publicIp.trim();

        for (OfficeSite site : sites) {

            if (site.publicIpsCsv.isBlank()) {
                continue;
            }

            boolean matched =
                    Stream.of(
                                    site.publicIpsCsv.split(","))
                            .map(String::trim)
                            .filter(value ->
                                    !value.isBlank())
                            .anyMatch(
                                    trimmed::equals);

            if (matched) {
                return site;
            }
        }

        return null;
    }

    private static boolean anySsidConfigured(
            List<OfficeSite> sites) {

        for (OfficeSite site : sites) {

            if (!site.ssidsCsv.isBlank()) {
                return true;
            }
        }

        return !EnvConfig.get(
                        "OFFICE_SSIDS",
                        "")
                .trim()
                .isBlank();
    }

    private static OfficeSite findSiteBySsid(
            List<OfficeSite> sites,
            String currentSsid) {

        if (currentSsid == null
                || currentSsid.isBlank()) {

            return null;
        }

        for (OfficeSite site : sites) {

            if (site.ssidsCsv.isBlank()) {
                continue;
            }

            boolean matched =
                    Stream.of(
                                    site.ssidsCsv.split(","))
                            .map(String::trim)
                            .filter(value ->
                                    !value.isBlank())
                            .anyMatch(
                                    currentSsid::equalsIgnoreCase);

            if (matched) {
                return site;
            }
        }

        return null;
    }

    private static void putOfficeLocation(
            Map<String, Object> result,
            OfficeSite site) {

        result.put(
                "city",
                emptyToNull(site.city));

        result.put(
                "region",
                emptyToNull(site.region));

        result.put(
                "country",
                emptyToNull(site.country));

        result.put(
                "address",
                emptyToNull(
                        buildAddress(
                                site.city,
                                site.region,
                                site.country)));

        result.put(
                "latitude",
                site.latitude);

        result.put(
                "longitude",
                site.longitude);

        result.put("accuracy", null);
        result.put("zip", null);
    }

    // -----------------------------------------------------------------
    // LEGACY OVERRIDES
    // -----------------------------------------------------------------

    /**
     * Legacy single-bucket public IP list.
     *
     * Kept only for public IPs that are not listed under any
     * OFFICE_<SITE>_PUBLIC_IPS entry.
     */
    private static boolean applyLegacyPublicIpOverride(
            Map<String, Object> result,
            String publicIp) {

        if (publicIp == null
                || publicIp.isBlank()) {

            return false;
        }

        String officePublicIps =
                EnvConfig.get(
                        "OFFICE_PUBLIC_IPS",
                        "").trim();

        if (officePublicIps.isBlank()) {

            return false;
        }

        String trimmed =
                publicIp.trim();

        boolean matched =
                Stream.of(
                                officePublicIps.split(","))
                        .map(String::trim)
                        .filter(value ->
                                !value.isBlank())
                        .anyMatch(
                                trimmed::equals);

        if (!matched) {

            return false;
        }

        String city =
                EnvConfig.get(
                        "OFFICE_PUBLIC_IP_CITY",
                        "Pune").trim();

        String region =
                EnvConfig.get(
                        "OFFICE_PUBLIC_IP_REGION",
                        "Maharashtra").trim();

        String country =
                EnvConfig.get(
                        "OFFICE_PUBLIC_IP_COUNTRY",
                        "India").trim();

        result.put("city", emptyToNull(city));
        result.put("region", emptyToNull(region));
        result.put("country", emptyToNull(country));

        result.put(
                "address",
                emptyToNull(
                        buildAddress(
                                city,
                                region,
                                country)));

        result.put("zip", null);
        result.put("accuracy", null);

        result.put(
                "latitude",
                EnvConfig.getDouble(
                        "OFFICE_PUBLIC_IP_LAT",
                        18.511033));

        result.put(
                "longitude",
                EnvConfig.getDouble(
                        "OFFICE_PUBLIC_IP_LNG",
                        73.925595));

        log.warn(
                "Public IP {} matched only the legacy OFFICE_PUBLIC_IPS list. "
                        + "Move it under an OFFICE_<SITE>_PUBLIC_IPS entry.",
                publicIp);

        return true;
    }

    /**
     * Legacy single-bucket SSID list.
     */
    private static boolean applyLegacySsidOverride(
            Map<String, Object> result,
            String currentSsid) {

        if (currentSsid == null
                || currentSsid.isBlank()) {

            return false;
        }

        String configuredSsids =
                EnvConfig.get(
                        "OFFICE_SSIDS",
                        "").trim();

        if (configuredSsids.isBlank()) {

            return false;
        }

        boolean matched =
                Stream.of(
                                configuredSsids.split(","))
                        .map(String::trim)
                        .filter(value ->
                                !value.isBlank())
                        .anyMatch(
                                currentSsid::equalsIgnoreCase);

        if (!matched) {

            return false;
        }

        String city =
                EnvConfig.get(
                        "OFFICE_SSID_CITY",
                        "Pune").trim();

        String region =
                EnvConfig.get(
                        "OFFICE_SSID_REGION",
                        "Maharashtra").trim();

        String country =
                EnvConfig.get(
                        "OFFICE_SSID_COUNTRY",
                        "India").trim();

        result.put("city", emptyToNull(city));
        result.put("region", emptyToNull(region));
        result.put("country", emptyToNull(country));

        result.put(
                "address",
                emptyToNull(
                        buildAddress(
                                city,
                                region,
                                country)));

        result.put("zip", null);
        result.put("accuracy", null);

        result.put(
                "latitude",
                EnvConfig.getDouble(
                        "OFFICE_SSID_LAT",
                        18.511033));

        result.put(
                "longitude",
                EnvConfig.getDouble(
                        "OFFICE_SSID_LNG",
                        73.925595));

        log.warn(
                "SSID '{}' matched only the legacy OFFICE_SSIDS list. "
                        + "Move it under an OFFICE_<SITE>_SSIDS entry.",
                currentSsid);

        return true;
    }

    // -----------------------------------------------------------------
    // PUBLIC IP
    // -----------------------------------------------------------------

    public static String getPublicIp() {

        try {

            String ip =
                    httpGet(
                            "https://api.ipify.org",
                            "ActivePulse/1.0");

            if (ip != null
                    && !ip.isBlank()
                    && isValidIpv4(ip.trim())) {

                return ip.trim();
            }

        } catch (Exception e) {

            log.debug(
                    "getPublicIp failed: {}",
                    e.getMessage());
        }

        return "";
    }

    // -----------------------------------------------------------------
    // SSID
    // -----------------------------------------------------------------

    private static String getCurrentSsid() {

        if (!isWindows()) {

            return "";
        }

        Process process = null;

        try {

            process =
                    new ProcessBuilder(
                            "netsh",
                            "wlan",
                            "show",
                            "interfaces")
                            .redirectErrorStream(true)
                            .start();

            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         process.getInputStream(),
                                         StandardCharsets.UTF_8))) {

                String line;

                while ((line =
                        reader.readLine()) != null) {

                    String trimmed =
                            line.trim();

                    int index =
                            trimmed.indexOf(':');

                    if (index <= 0) {
                        continue;
                    }

                    String key =
                            trimmed.substring(
                                            0,
                                            index)
                                    .trim();

                    if ("SSID".equalsIgnoreCase(key)) {

                        return trimmed.substring(
                                        index + 1)
                                .trim();
                    }
                }
            }

            process.waitFor(
                    4,
                    TimeUnit.SECONDS);

        } catch (Exception e) {

            log.debug(
                    "getCurrentSsid failed: {}",
                    e.getMessage());

        } finally {

            if (process != null
                    && process.isAlive()) {

                process.destroyForcibly();
            }
        }

        return "";
    }

    // -----------------------------------------------------------------
    // SUBNET
    // -----------------------------------------------------------------

    private static boolean matchesAnySubnet(
            String ip,
            String subnetsCsv) {

        if (ip == null
                || ip.isBlank()
                || subnetsCsv == null
                || subnetsCsv.isBlank()) {

            return false;
        }

        long ipLong;

        try {

            ipLong =
                    ipToLong(ip);

        } catch (Exception e) {

            return false;
        }

        return Stream.of(
                        subnetsCsv.split(","))
                .map(String::trim)
                .filter(value ->
                        !value.isBlank())
                .anyMatch(entry ->
                        matchesSubnetEntry(
                                ip,
                                ipLong,
                                entry));
    }

    private static boolean matchesSubnetEntry(
            String ip,
            long ipLong,
            String entry) {

        try {

            if (entry.contains("/")) {

                String[] parts =
                        entry.split("/");

                if (parts.length != 2) {
                    return false;
                }

                int bits =
                        Integer.parseInt(
                                parts[1].trim());

                if (bits < 0
                        || bits > 32) {

                    return false;
                }

                long network =
                        ipToLong(
                                padToFullIp(
                                        parts[0].trim()));

                long mask =
                        bits == 0
                                ? 0L
                                : (0xFFFFFFFFL
                                << (32 - bits))
                                & 0xFFFFFFFFL;

                return (ipLong & mask)
                        == (network & mask);
            }

            int octetCount =
                    entry.split("\\.").length;

            if (octetCount == 4) {

                return ipToLong(entry)
                        == ipLong;
            }

            String prefix =
                    entry.endsWith(".")
                            ? entry
                            : entry + ".";

            return ip.startsWith(prefix);

        } catch (Exception e) {

            log.debug(
                    "Skipping malformed subnet entry '{}'",
                    entry);

            return false;
        }
    }

    private static long ipToLong(
            String ip) {

        String[] octets =
                ip.trim().split("\\.");

        if (octets.length != 4) {

            throw new IllegalArgumentException(
                    "Not IPv4: " + ip);
        }

        long value = 0;

        for (String octet : octets) {

            int part =
                    Integer.parseInt(
                            octet.trim());

            if (part < 0
                    || part > 255) {

                throw new IllegalArgumentException(
                        "Invalid IPv4: " + ip);
            }

            value =
                    (value << 8)
                            | part;
        }

        return value & 0xFFFFFFFFL;
    }

    private static String padToFullIp(
            String maybePartial) {

        String[] octets =
                maybePartial.trim()
                        .split("\\.");

        StringBuilder result =
                new StringBuilder();

        for (int i = 0; i < 4; i++) {

            if (i > 0) {
                result.append('.');
            }

            result.append(
                    i < octets.length
                            && !octets[i].isBlank()
                            ? octets[i].trim()
                            : "0");
        }

        return result.toString();
    }

    // -----------------------------------------------------------------
    // PRIVATE IP
    // -----------------------------------------------------------------

    public static String getPrivateIp() {

        try {

            Enumeration<NetworkInterface>
                    interfaces =
                    NetworkInterface
                            .getNetworkInterfaces();

            if (interfaces != null) {

                for (NetworkInterface
                        networkInterface :
                        Collections.list(
                                interfaces)) {

                    if (!networkInterface.isUp()
                            || networkInterface.isLoopback()
                            || networkInterface.isVirtual()) {

                        continue;
                    }

                    for (InetAddress address :
                            Collections.list(
                                    networkInterface
                                            .getInetAddresses())) {

                        String hostAddress =
                                address.getHostAddress();

                        if (address.isSiteLocalAddress()
                                && !address
                                .isLoopbackAddress()
                                && hostAddress.indexOf(':') < 0) {

                            return hostAddress;
                        }
                    }
                }
            }

            String localHost =
                    InetAddress
                            .getLocalHost()
                            .getHostAddress();

            return isValidIpv4(localHost)
                    ? localHost
                    : "";

        } catch (Exception e) {

            log.debug(
                    "getPrivateIp failed: {}",
                    e.getMessage());

            return "";
        }
    }

    // -----------------------------------------------------------------
    // VALIDATION
    // -----------------------------------------------------------------

    private static boolean isValidIpv4(
            String ip) {

        try {

            ipToLong(ip);

            return true;

        } catch (Exception e) {

            return false;
        }
    }

    // -----------------------------------------------------------------
    // HTTP GET
    // -----------------------------------------------------------------

    private static String httpGet(
            String urlString,
            String userAgent) {

        HttpURLConnection connection =
                null;

        try {

            URL url =
                    URI.create(
                                    urlString)
                            .toURL();

            connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setRequestMethod(
                    "GET");

            connection.setConnectTimeout(
                    HTTP_TIMEOUT_MS);

            connection.setReadTimeout(
                    HTTP_TIMEOUT_MS);

            if (userAgent != null) {

                connection.setRequestProperty(
                        "User-Agent",
                        userAgent);
            }

            if (connection.getResponseCode()
                    != 200) {

                log.debug(
                        "HTTP GET returned status {} for {}",
                        connection.getResponseCode(),
                        urlString);

                return null;
            }

            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         connection
                                                 .getInputStream(),
                                         StandardCharsets.UTF_8))) {

                return reader.lines()
                        .collect(
                                Collectors.joining());
            }

        } catch (Exception e) {

            log.debug(
                    "HTTP GET {} failed: {}",
                    urlString,
                    e.getMessage());

            return null;

        } finally {

            if (connection != null) {

                connection.disconnect();
            }
        }
    }

    // -----------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------

    private static String emptyToNull(
            String value) {

        return value == null
                || value.isBlank()
                ? null
                : value;
    }

    private static boolean isWindows() {

        return System.getProperty(
                        "os.name",
                        "")
                .toLowerCase()
                .contains("win");
    }

    private static String buildAddress(
            String city,
            String region,
            String country) {

        return Stream.of(
                        city,
                        region,
                        country)
                .filter(value ->
                        value != null
                                && !value.isBlank())
                .collect(
                        Collectors.joining(
                                ", "));
    }
}