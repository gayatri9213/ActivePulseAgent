package com.activepulse.agent.scheduler.job;

import com.activepulse.agent.sync.SyncManager;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-of-day sync job — fires at 23:55 every day.
 * Ensures all remaining data for the current day is synced before midnight.
 * This handles the case where the last regular sync might be at 11:55 PM
 * but there could still be data generated between 11:55 PM and midnight.
 */
public class EndOfDaySyncJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(EndOfDaySyncJob.class);

    @Override
    public void execute(JobExecutionContext ctx) {
        try {
            log.info("🌅 End-of-day sync job triggered");
            SyncManager.getInstance().syncEndOfDay();
        } catch (Exception e) {
            log.error("EndOfDaySyncJob failed: {}", e.getMessage(), e);
        }
    }
}
