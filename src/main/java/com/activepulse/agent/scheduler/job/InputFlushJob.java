package com.activepulse.agent.scheduler.job;

import com.activepulse.agent.monitor.InputActivityRecorder;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz job — fires every 60 seconds.
 * Delegates to InputActivityRecorder.flush() which snapshots
 * keyboard/mouse counters and writes one row to activity_logs.
 */
public class InputFlushJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(InputFlushJob.class);

    @Override
    public void execute(JobExecutionContext ctx) {
        try {
            InputActivityRecorder.getInstance().flush();
        } catch (Exception e) {
            log.error("InputFlushJob failed: {}", e.getMessage(), e);
        }
    }
}
