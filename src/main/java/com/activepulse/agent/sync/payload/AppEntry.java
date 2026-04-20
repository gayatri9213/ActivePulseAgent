package com.activepulse.agent.sync.payload;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to payload: applications[]
 */
public class AppEntry {

    @JsonIgnore
    private long dbId;

    @JsonProperty("appName")         private String appName;
    @JsonProperty("windowTitle")     private String windowTitle;
    @JsonProperty("startTime")       private String startTime;
    @JsonProperty("endTime")         private String endTime;
    @JsonProperty("durationSeconds") private int    durationSeconds;

    public AppEntry() {}

    public AppEntry(long dbId, String appName, String windowTitle,
                    String startTime, String endTime, int durationSeconds) {
        this.dbId            = dbId;
        this.appName         = appName;
        this.windowTitle     = windowTitle;
        this.startTime       = startTime;
        this.endTime         = endTime;
        this.durationSeconds = durationSeconds;
    }

    public long   getDbId()            { return dbId; }
    public String getAppName()         { return appName; }
    public String getWindowTitle()     { return windowTitle; }
    public String getStartTime()       { return startTime; }
    public String getEndTime()         { return endTime; }
    public int    getDurationSeconds() { return durationSeconds; }
}