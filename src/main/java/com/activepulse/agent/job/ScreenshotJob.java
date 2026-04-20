package com.activepulse.agent.job;

import com.activepulse.agent.screenshot.ScreenshotCapture;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

public final class ScreenshotJob implements Job {
    @Override
    public void execute(JobExecutionContext ctx) {
        ScreenshotCapture.captureNow();
    }
}
