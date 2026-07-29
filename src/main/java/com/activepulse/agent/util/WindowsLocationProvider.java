package com.activepulse.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort Windows location via System.Device.Location (Wi-Fi/IP derived),
 * invoked through a short-lived PowerShell process.
 *
 * IMPORTANT: location consent is PER-USER. This works only when the agent runs
 * in a user session that has granted location access (Settings -> Privacy ->
 * Location -> "Let desktop apps access your location"). It returns null on ANY
 * failure — not Windows, disabled via agent.env, consent denied, blocked by
 * Group Policy, no location source, timeout, or parse error. Callers MUST treat
 * null as "unavailable" and fall through to the next location tier.
 *
 * This is why it CANNOT run from the admin install script (that context is a
 * different user with no consent) — it is a RUNTIME tier only.
 */
public final class WindowsLocationProvider {

    private static final Logger log = LoggerFactory.getLogger(WindowsLocationProvider.class);
    private static final int TIMEOUT_SEC = 15;

    private WindowsLocationProvider() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** @return {lat, lng} or null if unavailable for any reason. */
    public static double[] getCoordinates() {
        if (!isWindows()) return null;

        // Opt-in: off unless explicitly enabled in agent.env.
        if (!EnvConfig.getBool("WINDOWS_LOCATION_ENABLED", false)) {
            log.debug("Windows Location disabled (WINDOWS_LOCATION_ENABLED=false)");
            return null;
        }

        Process proc = null;
        try {
            // Query loop matches the command proven to work in the user's session:
            // it stops on Ready OR Denied, then reads Position.Location.
            String script =
                    "$ErrorActionPreference='Stop';" +
                            "Add-Type -AssemblyName System.Device;" +
                            "$w=New-Object System.Device.Location.GeoCoordinateWatcher;" +
                            "$w.Start();$t=0;" +
                            "while(($w.Status -ne 'Ready') -and ($w.Permission -ne 'Denied') -and ($t -lt 40))" +
                            "{Start-Sleep -Milliseconds 250;$t++};" +
                            "$l=$w.Position.Location;" +
                            "if($l.IsUnknown){Write-Output 'UNKNOWN'}" +
                            "else{Write-Output (\"{0},{1}\" -f $l.Latitude,$l.Longitude)};" +
                            "$w.Stop()";

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script);
            pb.redirectErrorStream(true);
            proc = pb.start();

            String output;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line.trim());
                output = sb.toString().trim();
            }

            if (!proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                log.warn("Windows Location timed out after {}s", TIMEOUT_SEC);
                return null;
            }

            if (output.isEmpty() || output.contains("UNKNOWN")) {
                log.info("Windows Location unavailable (denied / no source / off)");
                return null;
            }

            String[] parts = output.split(",");
            if (parts.length != 2) {
                log.debug("Windows Location unexpected output: {}", output);
                return null;
            }
            double lat = Double.parseDouble(parts[0].trim());
            double lng = Double.parseDouble(parts[1].trim());
            if (lat == 0.0 && lng == 0.0) return null;

            log.info("Windows Location -> {}, {}", lat, lng);
            return new double[]{lat, lng};

        } catch (Exception e) {
            log.warn("Windows Location failed: {}", e.getMessage());
            return null;
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
        }
    }
}