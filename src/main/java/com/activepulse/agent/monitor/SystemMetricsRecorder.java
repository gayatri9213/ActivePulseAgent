package com.activepulse.agent.monitor;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * SystemMetricsRecorder — stateless worker.
 * Scheduling is now owned by AgentScheduler → SystemMetricsJob.
 *
 * collectAndSave() is called by Quartz every 60 seconds.
 */
public class SystemMetricsRecorder {

    private static final Logger log = LoggerFactory.getLogger(SystemMetricsRecorder.class);

    private static final int CPU_SAMPLE_COUNT  = 4;
    private static final int CPU_SAMPLE_GAP_MS = 500; // 4 × 500ms = 2s total sampling

    private final SystemInfo              si;
    private final HardwareAbstractionLayer hal;
    private final CentralProcessor        cpu;
    private final GlobalMemory            memory;

    private long[] prevTicks;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile SystemMetricsRecorder instance;

    private SystemMetricsRecorder() {
        si       = new SystemInfo();
        hal      = si.getHardware();
        cpu      = hal.getProcessor();
        memory   = hal.getMemory();
        prevTicks = cpu.getSystemCpuLoadTicks();
    }

    public static SystemMetricsRecorder getInstance() {
        if (instance == null) {
            synchronized (SystemMetricsRecorder.class) {
                if (instance == null) instance = new SystemMetricsRecorder();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        log.info("SystemMetricsRecorder ready — driven by AgentScheduler.");
        logInstantSnapshot();
    }

    public void stop() {
        log.info("SystemMetricsRecorder stopped.");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Core work — called by SystemMetricsJob via Quartz
    // ─────────────────────────────────────────────────────────────────

    public void collectAndSave() {
        String recordedAt = TimeUtil.nowIST();
        double cpuPct     = measureCpuAverage();
        double memPct     = measureMemoryPercent();
        String netStatus  = measureNetworkStatus();

        log.info("SystemMetrics → cpu={}% mem={}% network={}",
                cpuPct, memPct, netStatus);
        save(recordedAt, cpuPct, memPct, netStatus);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Measurements
    // ─────────────────────────────────────────────────────────────────

    private double measureCpuAverage() {
        double total = 0.0;
        for (int i = 0; i < CPU_SAMPLE_COUNT; i++) {
            long[] ticks = cpu.getSystemCpuLoadTicks();
            total += cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0;
            prevTicks = ticks;
            if (i < CPU_SAMPLE_COUNT - 1) {
                try { Thread.sleep(CPU_SAMPLE_GAP_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        return Math.round((total / CPU_SAMPLE_COUNT) * 10.0) / 10.0;
    }

    private double measureMemoryPercent() {
        long total = memory.getTotal();
        long avail = memory.getAvailable();
        if (total == 0) return 0.0;
        return Math.round(((double)(total - avail) / total) * 1000.0) / 10.0;
    }

    /**
     * Network status — checks if any non-loopback NIC has:
     *   1. An assigned IPv4 address (means it's configured)
     *   2. Bytes received > 0 (means it has actually seen traffic)
     *
     * Speed alone is unreliable — virtual/disconnected adapters
     * often report a non-zero speed even when offline.
     */
    private String measureNetworkStatus() {
        try {
            List<NetworkIF> nics = hal.getNetworkIFs();
            for (NetworkIF nic : nics) {
                nic.updateAttributes();
                String name = nic.getName().toLowerCase();

                // Skip loopback and known virtual adapter prefixes
                if (name.startsWith("lo")
                        || name.contains("loopback")
                        || name.contains("vmware")
                        || name.contains("virtualbox")
                        || name.contains("vethernet")) continue;

                // Must have at least one real IPv4 address
                String[] ipv4 = nic.getIPv4addr();
                if (ipv4 == null || ipv4.length == 0) continue;

                // Must have received actual traffic
                if (nic.getBytesRecv() > 0) return "online";
            }
            return "offline";
        } catch (Exception e) {
            log.debug("Network check failed: {}", e.getMessage());
            return "unknown";
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB write
    // ─────────────────────────────────────────────────────────────────

    private void save(String recordedAt, double cpuPct,
                      double memPct, String netStatus) {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO system_metrics
                        (recorded_at, cpu_usage_avg, memory_usage_avg, network_status)
                    VALUES (?, ?, ?, ?)
                    """)) {
                ps.setString(1, recordedAt);
                ps.setDouble(2, cpuPct);
                ps.setDouble(3, memPct);
                ps.setString(4, netStatus);
                ps.executeUpdate();
                log.info("Saved system_metrics → cpu={}% mem={}% network={}",
                        cpuPct, memPct, netStatus);
            }
        } catch (SQLException e) {
            log.error("Failed to save system_metrics: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Instant snapshot on startup
    // ─────────────────────────────────────────────────────────────────

    private void logInstantSnapshot() {
        try {
            Thread.sleep(500);
            long[] ticks  = cpu.getSystemCpuLoadTicks();
            double cpuPct = cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0;
            prevTicks     = ticks;

            double memPct   = measureMemoryPercent();
            String netStatus = measureNetworkStatus();
            long   totalMb  = memory.getTotal()    / (1024 * 1024);
            long   availMb  = memory.getAvailable() / (1024 * 1024);
            long   usedMb   = totalMb - availMb;
            int    cores    = cpu.getLogicalProcessorCount();
            String cpuName  = cpu.getProcessorIdentifier().getName().trim();

            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("  System Snapshot");
            log.info("  CPU   : {} ({} cores)", cpuName, cores);
            log.info("  CPU % : {}%", String.format("%.1f", cpuPct));
            log.info("  RAM   : {}MB used / {}MB total  ({}%)", usedMb, totalMb, memPct);
            log.info("  Net   : {}", netStatus);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}