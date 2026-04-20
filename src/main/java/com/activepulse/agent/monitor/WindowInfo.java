package com.activepulse.agent.monitor;

/**
 * Immutable snapshot of the currently active window.
 *
 * isSameApp()    — compares app name only (used for URL flush logic)
 * isSameWindow() — compares app name + title (used for app_activity rows)
 */
public record WindowInfo(String appName, String windowTitle) {

    public static WindowInfo empty() {
        return new WindowInfo("", "");
    }

    public boolean isEmpty() {
        return appName == null || appName.isBlank();
    }

    /** True only if app name AND title are identical. */
    public boolean isSameWindow(WindowInfo other) {
        if (other == null) return false;
        return appName.equals(other.appName)
                && windowTitle.equals(other.windowTitle);
    }

    /** True if it's the same application regardless of title change. */
    public boolean isSameApp(WindowInfo other) {
        if (other == null) return false;
        return appName.equals(other.appName);
    }

    @Override
    public String toString() {
        return "WindowInfo{app='" + appName + "', title='" + windowTitle + "'}";
    }
}