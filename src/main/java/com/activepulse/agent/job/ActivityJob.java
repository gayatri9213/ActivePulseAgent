package com.activepulse.agent.job;

import com.activepulse.agent.monitor.ActivitySessionManager;
import com.activepulse.agent.monitor.ActiveWindowTracker;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

public final class ActivityJob implements Job {

    public static final String TRACKER_KEY = "windowTracker";

    @Override
    public void execute(JobExecutionContext ctx) {
        ActiveWindowTracker tracker = (ActiveWindowTracker) ctx.getJobDetail().getJobDataMap().get(TRACKER_KEY);
        if (tracker == null) return;
        ActivitySessionManager.getInstance().update(tracker.getActiveWindow());
    }
}
