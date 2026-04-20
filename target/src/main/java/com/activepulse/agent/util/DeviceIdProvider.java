package com.activepulse.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.ComputerSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Stable device identifier. Prefers hardware UUID (via OSHI), falls back to
 * a generated UUID stored in {dataDir}/device-id.
 */
public final class DeviceIdProvider {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdProvider.class);
    private static volatile String cached;

    private DeviceIdProvider() {}

    public static String get() {
        if (cached != null) return cached;
        synchronized (DeviceIdProvider.class) {
            if (cached != null) return cached;
            cached = resolve();
            return cached;
        }
    }

    private static String resolve() {
        // 1. Try hardware UUID
        try {
            ComputerSystem cs = new SystemInfo().getHardware().getComputerSystem();
            String hwUuid = cs.getHardwareUUID();
            if (hwUuid != null && !hwUuid.isBlank() && !hwUuid.equalsIgnoreCase("unknown")) {
                return "DEV-" + hwUuid.toUpperCase();
            }
        } catch (Throwable t) {
            log.warn("OSHI hardware UUID unavailable: {}", t.getMessage());
        }

        // 2. File-backed UUID
        Path file = PathResolver.dataDir().resolve("device-id");
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file).trim();
                if (!existing.isBlank()) return existing;
            }
            String generated = "DEV-" + UUID.randomUUID().toString().toUpperCase();
            Files.writeString(file, generated);
            return generated;
        } catch (Exception e) {
            log.warn("Failed to persist device-id: {}", e.getMessage());
            return "DEV-" + UUID.randomUUID().toString().toUpperCase();
        }
    }
}
