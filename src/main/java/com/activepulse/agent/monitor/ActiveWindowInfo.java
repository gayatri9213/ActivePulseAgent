package com.activepulse.agent.monitor;

public record ActiveWindowInfo(String processName, String title, String url) {
    public static ActiveWindowInfo unknown() {
        return new ActiveWindowInfo("unknown", "", "");
    }

    public boolean isSame(ActiveWindowInfo other) {
        if (other == null) return false;
        return safe(processName).equals(safe(other.processName))
            && safe(title).equals(safe(other.title));
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
