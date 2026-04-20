package com.activepulse.agent.sync.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to payload: screenshots[]
 */
public class ScreenshotEntry {

    @JsonIgnore
    private long dbId;

    @JsonProperty("fileName")   private String fileName;
    @JsonProperty("capturedAt") private String capturedAt;

    public ScreenshotEntry() {}

    public ScreenshotEntry(long dbId, String fileName, String capturedAt) {
        this.dbId       = dbId;
        this.fileName   = fileName;
        this.capturedAt = capturedAt;
    }

    public long   getDbId()       { return dbId; }
    public String getFileName()   { return fileName; }
    public String getCapturedAt() { return capturedAt; }
}
