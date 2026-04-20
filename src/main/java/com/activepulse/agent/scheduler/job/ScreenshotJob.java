package com.activepulse.agent.scheduler.job;

import com.activepulse.agent.monitor.ScreenshotRecorder;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz job — fires every 5 minutes.
 * Delegates to ScreenshotRecorder.captureAndSave() which
 * captures the screen and writes metadata to screenshots table.
 */
public class ScreenshotJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotJob.class);

    @Override
    public void execute(JobExecutionContext ctx) {
        try {
            ScreenshotRecorder.getInstance().captureAndSave();
        } catch (Exception e) {
            log.error("ScreenshotJob failed: {}", e.getMessage(), e);
        }
    }
}
