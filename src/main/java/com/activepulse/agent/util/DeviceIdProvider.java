package com.activepulse.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.ComputerSystem;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class DeviceIdProvider {
    private static final Logger log = LoggerFactory.getLogger(DeviceIdProvider.class);
    private static volatile String cached;

    private DeviceIdProvider() {
    }

    public static String get() {
        if (cached != null) {
            return cached;
        }
        synchronized (DeviceIdProvider.class) {
            if (cached != null) {
                return cached;
            }
            cached = resolve();
            return cached;
        }
    }

    private static String resolve() {
        try {
            String osName = System.getProperty("os.name")
                    .toLowerCase();
            /*
             * Mac-specific:
             * Prefer Serial Number because it is stable
             * across reinstalls and machine reboots.
             */
            if (osName.contains("mac")) {
                String serialNumber = getMacSerialNumber();
                if (isValid(serialNumber)) {
                    log.info(
                            "Using Mac Serial Number as deviceId: {}",
                            serialNumber);
                    return "DEV-" +
                            serialNumber.toUpperCase();
                }
                log.warn(
                        "Mac Serial Number unavailable. Trying Hardware UUID...");
            }

            /*
             * Hardware UUID for all platforms
             */
            ComputerSystem cs = new SystemInfo()
                    .getHardware()
                    .getComputerSystem();

            String hardwareUuid = cs.getHardwareUUID();

            if (isValid(hardwareUuid)) {

                log.info(
                        "Using Hardware UUID as deviceId: {}",
                        hardwareUuid);

                return "DEV-" +
                        hardwareUuid.toUpperCase();
            }

        } catch (Exception e) {

            log.error(
                    "Error while resolving deviceId: {}",
                    e.getMessage(),
                    e);
        }

        /*
         * No random UUID generation
         * No file-based fallback
         */
        throw new RuntimeException(
                "Unable to determine a stable device ID for this machine.");
    }

    private static String getMacSerialNumber() {

        try {

            Process process = Runtime.getRuntime().exec(
                    new String[] {
                            "sh",
                            "-c",
                            "system_profiler SPHardwareDataType | awk '/Serial Number/ {print $NF}'"
                    });

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            process.getInputStream()));

            String serialNumber = reader.readLine();

            process.waitFor();

            if (isValid(serialNumber)) {
                return serialNumber.trim();
            }

        } catch (Exception e) {

            log.warn(
                    "Failed to fetch Mac Serial Number: {}",
                    e.getMessage());
        }

        return null;
    }

    private static boolean isValid(String value) {

        return value != null
                && !value.isBlank()
                && !"unknown".equalsIgnoreCase(value)
                && !"null".equalsIgnoreCase(value);
    }
}
