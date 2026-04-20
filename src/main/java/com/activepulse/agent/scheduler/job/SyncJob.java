package com.activepulse.agent.scheduler.job;

import com.activepulse.agent.sync.SyncManager;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz job — fires every 5 minutes.
 * Builds payload from unsynced DB rows and writes to
 * ~/.activepulse/sync/sent/<syncId>_<timestamp>.json
 */
public class SyncJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(SyncJob.class);

    @Override
    public void execute(JobExecutionContext ctx) {
        try {
            SyncManager.getInstance().sync();
        } catch (Exception e) {
            log.error("SyncJob failed: {}", e.getMessage(), e);
        }
    }
}