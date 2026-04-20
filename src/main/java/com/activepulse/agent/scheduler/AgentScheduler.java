package com.activepulse.agent.scheduler;

import com.activepulse.agent.scheduler.job.EndOfDaySyncJob;
import com.activepulse.agent.scheduler.job.InputFlushJob;
import com.activepulse.agent.scheduler.job.ScreenshotJob;
import com.activepulse.agent.scheduler.job.SyncJob;
import com.activepulse.agent.scheduler.job.SystemMetricsJob;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.CronScheduleBuilder.cronSchedule;

/**
 * AgentScheduler — single Quartz scheduler that owns ALL periodic jobs.
 *
 * Job timetable:
 * ┌──────────────────────┬─────────────┬────────────────────────────┐
 * │ Job                  │ Interval    │ Writes to                  │
 * ├──────────────────────┼─────────────┼────────────────────────────┤
 * │ InputFlushJob        │ 60 seconds  │ activity_logs              │
 * │ SystemMetricsJob     │ 60 seconds  │ system_metrics             │
 * │ ScreenshotJob        │  5 minutes  │ screenshots + disk         │
 * │ SyncJob              │  10 minutes │ sync/sent/*.json           │
 * │ EndOfDaySyncJob      │ 23:55 daily │ end-of-day data sync       │
 * └──────────────────────┴─────────────┴────────────────────────────┘
 */
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    private static final int INPUT_INTERVAL_SECS      = 20;  // flush every 20s
    private static final int SYSTEM_METRICS_SECS      = 60;
    private static final int SCREENSHOT_INTERVAL_MINS = 5;   // every 5 min
    private static final int SYNC_INTERVAL_MINS       = 10;  // every 10 min

    private Scheduler scheduler;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile AgentScheduler instance;
    private AgentScheduler() {}

    public static AgentScheduler getInstance() {
        if (instance == null) {
            synchronized (AgentScheduler.class) {
                if (instance == null) instance = new AgentScheduler();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        try {
            StdSchedulerFactory factory = new StdSchedulerFactory();
            factory.initialize(
                    AgentScheduler.class.getResourceAsStream("/quartz.properties"));

            scheduler = factory.getScheduler();

            // ── 1. Input flush — every 20s ───────────────────────────
            registerJob(InputFlushJob.class,
                    "input-flush", "monitors",
                    simpleSchedule()
                            .withIntervalInSeconds(INPUT_INTERVAL_SECS)
                            .repeatForever(),
                    INPUT_INTERVAL_SECS
            );

            // ── 2. System metrics — every 60s, starts after 60s ──────
            registerJob(SystemMetricsJob.class,
                    "system-metrics", "monitors",
                    simpleSchedule()
                            .withIntervalInSeconds(SYSTEM_METRICS_SECS)
                            .repeatForever(),
                    SYSTEM_METRICS_SECS
            );

            // ── 3. Screenshot — every 5 min, fires immediately ────────
            registerJob(ScreenshotJob.class,
                    "screenshot", "monitors",
                    simpleSchedule()
                            .withIntervalInMinutes(SCREENSHOT_INTERVAL_MINS)
                            .repeatForever(),
                    0   // 0 = fire immediately on start
            );

            // ── 4. Sync — every 10 min, starts after 10 min ───────────
            registerJob(SyncJob.class,
                    "sync", "sync",
                    simpleSchedule()
                            .withIntervalInMinutes(SYNC_INTERVAL_MINS)
                            .repeatForever(),
                    SYNC_INTERVAL_MINS * 60  // wait one full interval first
            );

            // ── 5. End-of-day sync — every day at 23:55 ──────────────────
            registerCronJob(EndOfDaySyncJob.class,
                    "end-of-day-sync", "sync",
                    "0 55 23 * * ?"  // 23:55 every day
            );

            scheduler.start();

            log.info("AgentScheduler started.");
            log.info("  input-flush      : every {}s", INPUT_INTERVAL_SECS);
            log.info("  system-metrics   : every {}s", SYSTEM_METRICS_SECS);
            log.info("  screenshot       : every {}m (immediate)", SCREENSHOT_INTERVAL_MINS);
            log.info("  sync             : every {}m", SYNC_INTERVAL_MINS);
            log.info("  end-of-day-sync  : daily at 23:55");

        } catch (SchedulerException e) {
            log.error("Failed to start AgentScheduler", e);
            throw new RuntimeException("AgentScheduler start failed", e);
        }
    }

    public void stop() {
        if (scheduler != null) {
            try {
                scheduler.shutdown(true);
                log.info("AgentScheduler stopped.");
            } catch (SchedulerException e) {
                log.warn("Error stopping AgentScheduler: {}", e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helper
    // ─────────────────────────────────────────────────────────────────

    private void registerJob(Class<? extends Job> jobClass,
                             String name, String group,
                             SimpleScheduleBuilder schedule,
                             int initialDelaySeconds) throws SchedulerException {

        JobDetail job = newJob(jobClass)
                .withIdentity(name, group)
                .build();

        TriggerBuilder<SimpleTrigger> tb = newTrigger()
                .withIdentity(name + "-trigger", group)
                .withSchedule(schedule);

        if (initialDelaySeconds > 0) {
            tb.startAt(DateBuilder.futureDate(
                    initialDelaySeconds, DateBuilder.IntervalUnit.SECOND));
        } else {
            tb.startNow();
        }

        scheduler.scheduleJob(job, tb.build());
        log.debug("Registered job: {}/{}", group, name);
    }

    private void registerCronJob(Class<? extends Job> jobClass,
                                String name, String group,
                                String cronExpression) throws SchedulerException {

        JobDetail job = newJob(jobClass)
                .withIdentity(name, group)
                .build();

        Trigger trigger = newTrigger()
                .withIdentity(name + "-trigger", group)
                .withSchedule(cronSchedule(cronExpression))
                .build();

        scheduler.scheduleJob(job, trigger);
        log.debug("Registered cron job: {}/{} with expression: {}", group, name, cronExpression);
    }
}