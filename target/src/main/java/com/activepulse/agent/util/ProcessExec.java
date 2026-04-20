package com.activepulse.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Simple subprocess runner with timeout. Used for shelling out to
 * PowerShell, osascript, xdotool, quser, etc.
 */
public final class ProcessExec {

    private static final Logger log = LoggerFactory.getLogger(ProcessExec.class);

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    private ProcessExec() {}

    public static Result run(long timeoutSeconds, String... cmd) {
        return run(timeoutSeconds, List.of(cmd));
    }

    public static Result run(long timeoutSeconds, List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = null;
        try {
            p = pb.start();
            String out = read(p.getInputStream());
            String err = read(p.getErrorStream());
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Command timed out after {}s: {}", timeoutSeconds, cmd);
                return new Result(-1, out, "timeout");
            }
            return new Result(p.exitValue(), out.trim(), err.trim());
        } catch (Exception e) {
            log.debug("Command failed {}: {}", cmd, e.getMessage());
            return new Result(-1, "", e.getMessage());
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static String read(java.io.InputStream is) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
