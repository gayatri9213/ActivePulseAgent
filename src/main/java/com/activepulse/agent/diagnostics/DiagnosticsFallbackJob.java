package com.activepulse.agent.diagnostics;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz job that runs the 12 PM daily fallback for diagnostics logs.
 *
 * Scheduled by Main.java with cron from EnvConfig("DIAGNOSTICS_UPLOAD_CRON").
 * Default: "0 0 12 * * ?" (every day at 12:00:00 IST).
 *
 * Behavior:
 *   - Skips today's upload if shutdown handler already synced today.
 *   - Otherwise uploads today's partial log.
 *   - Always backfills any unsynced days from the last 7 days.
 *
 * Replaces the previous 2-hourly DiagnosticsUploadJob.
 */
public final class DiagnosticsFallbackJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsFallbackJob.class);

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            log.info("DiagnosticsFallbackJob fired at {}", context.getFireTime());
            DiagnosticsUploader.getInstance().uploadDailyFallback();
        } catch (Throwable t) {
            log.error("DiagnosticsFallbackJob failed: {}", t.getMessage(), t);
            // Don't rethrow — Quartz would keep firing misfires
        }
    }
}