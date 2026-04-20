package com.activepulse.agent.util;

public enum OsType {
    WINDOWS, MACOS, LINUX, UNKNOWN;

    public static OsType current() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win"))   return WINDOWS;
        if (os.contains("mac"))   return MACOS;
        if (os.contains("nux") || os.contains("nix")) return LINUX;
        return UNKNOWN;
    }

    public static String displayName() {
        return switch (current()) {
            case WINDOWS -> "Windows";
            case MACOS   -> "macOS";
            case LINUX   -> "Linux";
            default      -> "Unknown";
        };
    }
}
