package com.activepulse.agent.autostart;

import com.activepulse.agent.util.OsType;

public final class AutostartFactory {
    private AutostartFactory() {}

    public static AutostartManager create() {
        return switch (OsType.current()) {
            case WINDOWS -> new WindowsAutostartManager();
            case MACOS   -> new MacAutostartManager();
            case LINUX   -> new LinuxAutostartManager();
            default      -> new AutostartManager() {
                public boolean install()    { return false; }
                public boolean uninstall()  { return false; }
                public boolean isInstalled() { return false; }
            };
        };
    }
}
