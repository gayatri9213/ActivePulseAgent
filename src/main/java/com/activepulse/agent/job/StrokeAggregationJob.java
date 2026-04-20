package com.activepulse.agent.job;

import com.activepulse.agent.db.StrokeDao;
import com.activepulse.agent.monitor.KeyboardMouseTracker;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

public final class StrokeAggregationJob implements Job {
    @Override
    public void execute(JobExecutionContext ctx) {
        int[] counts = KeyboardMouseTracker.getInstance().drain();
        if (counts[0] == 0 && counts[1] == 0) return;
        StrokeDao.insert(counts[0], counts[1]);
    }
}
