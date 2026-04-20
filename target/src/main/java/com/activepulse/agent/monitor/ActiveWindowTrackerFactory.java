package com.activepulse.agent.monitor;

import com.activepulse.agent.platform.linux.LinuxActiveWindowTracker;
import com.activepulse.agent.platform.macos.MacActiveWindowTracker;
import com.activepulse.agent.platform.windows.WindowsActiveWindowTracker;
import com.activepulse.agent.util.OsType;

public final class ActiveWindowTrackerFactory {
    private ActiveWindowTrackerFactory() {}

    public static ActiveWindowTracker create() {
        return switch (OsType.current()) {
            case WINDOWS -> new WindowsActiveWindowTracker();
            case MACOS   -> new MacActiveWindowTracker();
            case LINUX   -> new LinuxActiveWindowTracker();
            default      -> () -> ActiveWindowInfo.unknown();
        };
    }
}
