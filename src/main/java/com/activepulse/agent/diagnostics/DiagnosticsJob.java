package com.activepulse.agent.diagnostics;

import org.quartz.Job;
import org.quartz.JobExecutionContext;

/**
 * Quartz job that triggers the diagnostic log upload cycle.
 *
 * Registered in JobScheduler with two triggers:
 *   1. Daily cron at 23:00 IST — normal upload time
 *   2. One-shot 60 seconds after startup — catch-up if machine
 *      was off at 23:00 the previous day
 *
 * All error handling lives inside DiagnosticsUploader.uploadLogs()
 * so this class stays trivially simple.
 */
public class DiagnosticsJob implements Job {

    @Override
    public void execute(JobExecutionContext context) {
        DiagnosticsUploader.getInstance().uploadLogs();
    }
}