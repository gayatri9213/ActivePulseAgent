package com.activepulse.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Environment config loader. Reads agent.env from (in order):
 *   1. System property -Dactivepulse.env=/path/to/agent.env
 *   2. Current working directory / agent.env
 *   3. Alongside the running JAR / agent.env
 *   4. Classpath resource agent.env.template (fallback defaults)
 *
 * Also overlays real environment variables on top (they win over file values).
 */
public final class EnvConfig {

    private static final Logger log = LoggerFactory.getLogger(EnvConfig.class);
    private static final Properties props = new Properties();
    private static volatile boolean loaded = false;

    private EnvConfig() {}

    public static synchronized void load() {
        if (loaded) return;

        Path[] candidates = resolveCandidatePaths();
        boolean fileLoaded = false;
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                    log.info("Loaded config from {}", p.toAbsolutePath());
                    fileLoaded = true;
                    break;
                } catch (IOException e) {
                    log.warn("Failed reading {}: {}", p, e.getMessage());
                }
            }
        }

        if (!fileLoaded) {
            try (InputStream in = EnvConfig.class.getResourceAsStream("/agent.env.template")) {
                if (in != null) {
                    props.load(in);
                    log.info("Loaded default config from bundled agent.env.template");
                }
            } catch (IOException e) {
                log.warn("Unable to load bundled defaults: {}", e.getMessage());
            }
        }

        // Environment variables override file
        for (String name : props.stringPropertyNames()) {
            String envVal = System.getenv(name);
            if (envVal != null && !envVal.isBlank()) {
                props.setProperty(name, envVal);
            }
        }

        loaded = true;
    }

    private static Path[] resolveCandidatePaths() {
        Path[] out = new Path[3];
        String sysProp = System.getProperty("activepulse.env");
        if (sysProp != null && !sysProp.isBlank()) {
            out[0] = Paths.get(sysProp);
        }
        out[1] = Paths.get("agent.env");
        try {
            Path jarDir = Paths.get(EnvConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            if (jarDir != null) out[2] = jarDir.resolve("agent.env");
        } catch (Exception ignored) {}
        return out;
    }

    public static String get(String key, String fallback) {
        if (!loaded) load();
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    public static int getInt(String key, int fallback) {
        try { return Integer.parseInt(get(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static long getLong(String key, long fallback) {
        try { return Long.parseLong(get(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static boolean getBool(String key, boolean fallback) {
        String v = get(key, String.valueOf(fallback)).toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes");
    }

    public static double getDouble(String key, double fallback) {
        try { return Double.parseDouble(get(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static boolean isSet(String key) {
        if (!loaded) load();
        String v = props.getProperty(key);
        return v != null && !v.isBlank();
    }
}
