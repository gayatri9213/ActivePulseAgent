package com.activepulse.agent.sync;

public class SyncResponse {

    private String message;
    private String syncId;
    private int activityLogCount;
    private int keyboardMouseStrokeCount;
    private Settings settings;

    public String getMessage() {
        return message;
    }

    public String getSyncId() {
        return syncId;
    }

    public int getActivityLogCount() {
        return activityLogCount;
    }

    public int getKeyboardMouseStrokeCount() {
        return keyboardMouseStrokeCount;
    }

    public Settings getSettings() {
        return settings;
    }

    public static class Settings {

        private boolean logsEnabled;
        private boolean screenshotsEnabled;

        public boolean isLogsEnabled() {
            return logsEnabled;
        }

        public boolean isScreenshotsEnabled() {
            return screenshotsEnabled;
        }
    }
}
