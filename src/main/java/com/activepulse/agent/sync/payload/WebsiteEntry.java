package com.activepulse.agent.sync.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to payload: websites[]
 */
public class WebsiteEntry {

    @JsonIgnore
    private long dbId;

    @JsonProperty("url")             private String url;
    @JsonProperty("domain")          private String domain;
    @JsonProperty("startTime")       private String startTime;
    @JsonProperty("endTime")         private String endTime;
    @JsonProperty("durationSeconds") private int    durationSeconds;

    public WebsiteEntry() {}

    public WebsiteEntry(long dbId, String url, String domain,
                        String startTime, String endTime, int durationSeconds) {
        this.dbId            = dbId;
        this.url             = url;
        this.domain          = domain;
        this.startTime       = startTime;
        this.endTime         = endTime;
        this.durationSeconds = durationSeconds;
    }

    public long   getDbId()            { return dbId; }
    public String getUrl()             { return url; }
    public String getDomain()          { return domain; }
    public String getStartTime()       { return startTime; }
    public String getEndTime()         { return endTime; }
    public int    getDurationSeconds() { return durationSeconds; }
}
