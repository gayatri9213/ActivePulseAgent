package com.activepulse.agent.sync.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to payload: activityLogs[]
 */
public class ActivityLogEntry {

    @JsonIgnore
    private long dbId;

    @JsonProperty("startTime")      private String startTime;
    @JsonProperty("endTime")        private String endTime;
    @JsonProperty("activeSeconds")  private int    activeSeconds;
    @JsonProperty("idleSeconds")    private int    idleSeconds;
    @JsonProperty("keyboardClicks") private int    keyboardClicks;
    @JsonProperty("mouseClicks")    private int    mouseClicks;
    @JsonProperty("mouseMovement")  private double mouseMovement;

    public ActivityLogEntry() {}

    public ActivityLogEntry(long dbId, String startTime, String endTime,
                            int activeSeconds, int idleSeconds,
                            int keyboardClicks, int mouseClicks,
                            double mouseMovement) {
        this.dbId           = dbId;
        this.startTime      = startTime;
        this.endTime        = endTime;
        this.activeSeconds  = activeSeconds;
        this.idleSeconds    = idleSeconds;
        this.keyboardClicks = keyboardClicks;
        this.mouseClicks    = mouseClicks;
        this.mouseMovement  = mouseMovement;
    }

    public long   getDbId()           { return dbId; }
    public String getStartTime()      { return startTime; }
    public String getEndTime()        { return endTime; }
    public int    getActiveSeconds()  { return activeSeconds; }
    public int    getIdleSeconds()    { return idleSeconds; }
    public int    getKeyboardClicks() { return keyboardClicks; }
    public int    getMouseClicks()    { return mouseClicks; }
    public double getMouseMovement()  { return mouseMovement; }
}
