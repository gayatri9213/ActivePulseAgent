package com.activepulse.agent.util;

/**
 * Central switch for LIVE vs TEST mode.
 *
 * Resolution order:
 *   1. JVM system property  -Dactivepulse.mode=test   (set in IDE run-config;
 *      a production launcher never passes it, so installs are always live)
 *   2. agent.env            AGENT_MODE=test
 *   3. default              live
 *
 * TEST mode effects:
 *   - DatabaseManager uses a separate DB file (activepulse-test.db)
 *   - SyncManager.sync() no-ops (nothing uploaded to the portal)
 */
public final class AgentMode {

    private AgentMode() {}

    public static boolean isTest() {
        String sys = System.getProperty("activepulse.mode", "");
        if (sys != null && !sys.isBlank()) {
            return "test".equalsIgnoreCase(sys.trim());
        }
        return "test".equalsIgnoreCase(EnvConfig.get("AGENT_MODE", "live").trim());
    }

    public static String current() {
        return isTest() ? "test" : "live";
    }
}