package com.activepulse.agent.job;

import com.activepulse.agent.monitor.ActiveWindowTracker;
import com.activepulse.agent.monitor.ActiveWindowTrackerFactory;
import com.activepulse.agent.util.EnvConfig;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

/**
 * Central scheduler — starts Quartz and schedules all periodic jobs
 * using intervals from agent.env.
 */
public final class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);
    private Scheduler scheduler;

    public void start() throws SchedulerException {
        scheduler = new StdSchedulerFactory().getScheduler();

        ActiveWindowTracker tracker = ActiveWindowTrackerFactory.create();

        if (EnvConfig.getBool("ENABLE_WINDOW_TRACKING", true)) {
            int sec = EnvConfig.getInt("ACTIVITY_POLL_SECONDS", 5);
            JobDataMap map = new JobDataMap();
            map.put(ActivityJob.TRACKER_KEY, tracker);
            JobDetail job = newJob(ActivityJob.class)
                    .withIdentity("activityJob", "capture")
                    .usingJobData(map)
                    .build();
            Trigger t = newTrigger()
                    .withIdentity("activityTrigger", "capture")
                    .startNow()
                    .withSchedule(simpleSchedule().withIntervalInSeconds(sec).repeatForever())
                    .build();
            scheduler.scheduleJob(job, t);
            log.info("Scheduled activity poll every {}s", sec);
        }

        if (EnvConfig.getBool("ENABLE_KEYBOARD_MOUSE", true)) {
            int sec = EnvConfig.getInt("STROKE_AGGREGATE_SECONDS", 60);
            JobDetail job = newJob(StrokeAggregationJob.class)
                    .withIdentity("strokeJob", "capture")
                    .build();
            Trigger t = newTrigger()
                    .withIdentity("strokeTrigger", "capture")
                    .startNow()
                    .withSchedule(simpleSchedule().withIntervalInSeconds(sec).repeatForever())
                    .build();
            scheduler.scheduleJob(job, t);
            log.info("Scheduled stroke aggregation every {}s", sec);
        }

        if (EnvConfig.getBool("ENABLE_SCREENSHOTS", true)) {
            int sec = EnvConfig.getInt("SCREENSHOT_INTERVAL_SECONDS", 300);
            JobDetail job = newJob(ScreenshotJob.class)
                    .withIdentity("screenshotJob", "capture")
                    .build();
            Trigger t = newTrigger()
                    .withIdentity("screenshotTrigger", "capture")
                    .startAt(new java.util.Date(System.currentTimeMillis() + 30_000L))
                    .withSchedule(simpleSchedule().withIntervalInSeconds(sec).repeatForever())
                    .build();
            scheduler.scheduleJob(job, t);
            log.info("Scheduled screenshot capture every {}s", sec);
        }

        // Sync always on
        int syncSec = EnvConfig.getInt("SYNC_INTERVAL_SECONDS", 300);
        JobDetail syncJob = newJob(SyncJob.class)
                .withIdentity("syncJob", "sync")
                .build();
        Trigger syncTrigger = newTrigger()
                .withIdentity("syncTrigger", "sync")
                .startAt(new java.util.Date(System.currentTimeMillis() + 60_000L))
                .withSchedule(simpleSchedule().withIntervalInSeconds(syncSec).repeatForever())
                .build();
        scheduler.scheduleJob(syncJob, syncTrigger);
        log.info("Scheduled sync every {}s", syncSec);

        scheduler.start();
        log.info("Quartz scheduler started.");
    }

    public void stop() {
        if (scheduler == null) return;
        try {
            scheduler.shutdown(true);
            log.info("Scheduler stopped.");
        } catch (SchedulerException e) {
            log.warn("Scheduler shutdown error: {}", e.getMessage());
        }
    }
}
