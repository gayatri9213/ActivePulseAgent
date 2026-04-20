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
 * EnvConfig — loads agent.env configuration.
 *
 * Load priority (first found wins):
 *   1. agent.env next to the running JAR         (production override)
 *   2. agent.env in classpath (src/main/resources) (default / dev)
 *
 * Access values via EnvConfig.get("KEY") or EnvConfig.getInt("KEY", default).
 */
public class EnvConfig {

    private static final Logger log = LoggerFactory.getLogger(EnvConfig.class);
    private static final Properties props = new Properties();

    static {
        load();
    }

    private EnvConfig() {}

    private static void load() {
        // ── 1. Try external agent.env next to JAR ─────────────────────
        try {
            Path jarDir = Paths.get(
                    EnvConfig.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI()).getParent();
            Path external = jarDir.resolve("agent.env");
            if (Files.exists(external)) {
                try (InputStream is = Files.newInputStream(external)) {
                    props.load(is);
                    log.info("EnvConfig loaded from external: {}", external);
                    return;
                }
            }
        } catch (Exception ignored) {}

        // ── 2. Fall back to classpath agent.env ───────────────────────
        try (InputStream is = EnvConfig.class
                .getClassLoader().getResourceAsStream("agent.env")) {
            if (is != null) {
                props.load(is);
                log.info("EnvConfig loaded from classpath.");
            } else {
                log.warn("agent.env not found — using defaults.");
            }
        } catch (IOException e) {
            log.error("Failed to load agent.env: {}", e.getMessage());
        }
    }

    public static String get(String key) {
        // System env takes priority over file
        String sysVal = System.getenv(key);
        if (sysVal != null && !sysVal.isBlank()) return sysVal;
        return props.getProperty(key, "");
    }

    public static String get(String key, String defaultValue) {
        String val = get(key);
        return (val == null || val.isBlank()) ? defaultValue : val;
    }

    public static int getInt(String key, int defaultValue) {
        try { return Integer.parseInt(get(key).trim()); }
        catch (Exception e) { return defaultValue; }
    }

    public static boolean isSet(String key) {
        String val = get(key);
        return val != null && !val.isBlank()
                && !val.equals("YOUR_API_KEY_HERE");
    }
}