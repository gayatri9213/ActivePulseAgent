package com.activepulse.agent.job;

import com.activepulse.agent.sync.SyncManager;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

public final class SyncJob implements Job {
    @Override
    public void execute(JobExecutionContext ctx) {
        SyncManager.getInstance().sync();
    }
}
