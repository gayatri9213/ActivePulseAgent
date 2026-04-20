package com.activepulse.agent.scheduler.job;

import com.activepulse.agent.monitor.SystemMetricsRecorder;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz job — fires every 60 seconds.
 * Delegates to SystemMetricsRecorder.collectAndSave() which
 * reads CPU, RAM, network and writes to system_metrics table.
 */
public class SystemMetricsJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(SystemMetricsJob.class);

    @Override
    public void execute(JobExecutionContext ctx) {
        try {
            SystemMetricsRecorder.getInstance().collectAndSave();
        } catch (Exception e) {
            log.error("SystemMetricsJob failed: {}", e.getMessage(), e);
        }
    }
}
