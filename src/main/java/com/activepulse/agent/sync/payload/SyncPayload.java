package com.activepulse.agent.sync.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Root sync payload — maps 1:1 to the server contract.
 *
 * {
 *   "syncId":         "SYNC-9a8b7c",
 *   "deviceId":       "DEV-12345",
 *   "userId":         "USR-56789",
 *   "organizationId": "ORG-1001",
 *   "agentVersion":   "1.0.0",
 *   "osType":         "Windows",
 *   "syncStartTime":  "2026-03-13T10:00:00Z",
 *   "syncEndTime":    "2026-03-13T10:05:00Z",
 *   "activityLogs":   [...],
 *   "applications":   [...],
 *   "websites":       [...],
 *   "screenshots":    [...],
 *   "systemMetrics":  {...}
 * }
 */
public class SyncPayload {

    @JsonProperty("syncId")
    private String syncId;

    @JsonProperty("deviceId")
    private String deviceId;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("organizationId")
    private String organizationId;

    @JsonProperty("agentVersion")
    private String agentVersion;

    @JsonProperty("osType")
    private String osType;

    @JsonProperty("syncStartTime")
    private String syncStartTime;

    @JsonProperty("syncEndTime")
    private String syncEndTime;

    @JsonProperty("activityLogs")
    private List<ActivityLogEntry> activityLogs;

    @JsonProperty("applications")
    private List<AppEntry> applications;

    @JsonProperty("websites")
    private List<WebsiteEntry> websites;

    @JsonProperty("screenshots")
    private List<ScreenshotEntry> screenshots;

    @JsonProperty("systemMetrics")
    private SystemMetricsEntry systemMetrics;

    // ── Builder pattern ───────────────────────────────────────────────
    private SyncPayload() {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final SyncPayload p = new SyncPayload();

        public Builder syncId(String v)         { p.syncId = v;         return this; }
        public Builder deviceId(String v)        { p.deviceId = v;        return this; }
        public Builder userId(String v)          { p.userId = v;          return this; }
        public Builder organizationId(String v)  { p.organizationId = v;  return this; }
        public Builder agentVersion(String v)    { p.agentVersion = v;    return this; }
        public Builder osType(String v)          { p.osType = v;          return this; }
        public Builder syncStartTime(String v)   { p.syncStartTime = v;   return this; }
        public Builder syncEndTime(String v)     { p.syncEndTime = v;     return this; }
        public Builder activityLogs(List<ActivityLogEntry> v) { p.activityLogs = v; return this; }
        public Builder applications(List<AppEntry> v)         { p.applications = v; return this; }
        public Builder websites(List<WebsiteEntry> v)         { p.websites = v;     return this; }
        public Builder screenshots(List<ScreenshotEntry> v)   { p.screenshots = v;  return this; }
        public Builder systemMetrics(SystemMetricsEntry v)    { p.systemMetrics = v; return this; }

        public SyncPayload build() { return p; }
    }

    // ── Getters ───────────────────────────────────────────────────────
    public String getSyncId()          { return syncId; }
    public String getDeviceId()         { return deviceId; }
    public String getUserId()           { return userId; }
    public String getOrganizationId()   { return organizationId; }
    public String getAgentVersion()     { return agentVersion; }
    public String getOsType()           { return osType; }
    public String getSyncStartTime()    { return syncStartTime; }
    public String getSyncEndTime()      { return syncEndTime; }
    public List<ActivityLogEntry> getActivityLogs()  { return activityLogs; }
    public List<AppEntry> getApplications()          { return applications; }
    public List<WebsiteEntry> getWebsites()          { return websites; }
    public List<ScreenshotEntry> getScreenshots()    { return screenshots; }
    public SystemMetricsEntry getSystemMetrics()     { return systemMetrics; }
}