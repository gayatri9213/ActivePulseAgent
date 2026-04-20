package com.activepulse.agent.sync.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to payload: systemMetrics (single object, not array)
 */
public class SystemMetricsEntry {

    @JsonIgnore
    private long dbId;

    @JsonProperty("cpuUsageAvg")    private double cpuUsageAvg;
    @JsonProperty("memoryUsageAvg") private double memoryUsageAvg;
    @JsonProperty("networkStatus")  private String networkStatus;

    public SystemMetricsEntry() {}

    public SystemMetricsEntry(long dbId, double cpuUsageAvg,
                              double memoryUsageAvg, String networkStatus) {
        this.dbId           = dbId;
        this.cpuUsageAvg    = cpuUsageAvg;
        this.memoryUsageAvg = memoryUsageAvg;
        this.networkStatus  = networkStatus;
    }

    public long   getDbId()            { return dbId; }
    public double getCpuUsageAvg()     { return cpuUsageAvg; }
    public double getMemoryUsageAvg()  { return memoryUsageAvg; }
    public String getNetworkStatus()   { return networkStatus; }
}
