package com.activepulse.agent.platform.windows;

import com.activepulse.agent.util.ProcessExec;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the actual logged-in AD user on Windows, not the Admin who ran the installer.
 *
 * Resolution chain (first success wins):
 *   1. WTSQuerySessionInformation on the active console session — works from both
 *      user context and SYSTEM context (important if we ever run as a service).
 *   2. Process owner of explorer.exe — catches RDP / remote desktop edge cases.
 *   3. System.getProperty("user.name") — last resort, logged as WARNING.
 *
 * Domain users are returned as "DOMAIN\\username". For local users, domain == machine name.
 */
public final class WindowsUserResolver {

    private static final Logger log = LoggerFactory.getLogger(WindowsUserResolver.class);

    public record Resolved(String domain, String username, String source) {
        public String qualified() {
            return (domain == null || domain.isBlank())
                    ? username
                    : domain + "\\" + username;
        }
    }

    private WindowsUserResolver() {}

    public static Resolved resolve() {
        Resolved r = viaWts();
        if (r != null) return r;

        r = viaExplorerOwner();
        if (r != null) return r;

        String fallback = System.getProperty("user.name", "unknown");
        log.warn("AD user detection fell back to user.name={} — may be Admin, not logged-in user!", fallback);
        return new Resolved("", fallback, "system-property");
    }

    // ─── 1. WTSQuerySessionInformation ───────────────────────────────
    private static Resolved viaWts() {
        try {
            int sessionId = Kernel32Ext.INSTANCE.WTSGetActiveConsoleSessionId();
            if (sessionId == 0xFFFFFFFF) {
                log.debug("No active console session (header/locked state).");
                return null;
            }

            String user   = queryWts(sessionId, 5 /* WTSUserName */);
            String domain = queryWts(sessionId, 7 /* WTSDomainName */);

            log.info("WTS raw → sessionId={} domain='{}' user='{}'", sessionId, domain, user);

            if (user == null || user.isBlank()) {
                log.debug("WTS returned blank username, trying next method.");
                return null;
            }
            return new Resolved(nullToEmpty(domain), user, "wts");
        } catch (Throwable t) {
            log.debug("WTS resolution failed: {}", t.getMessage());
            return null;
        }
    }

    private static String queryWts(int sessionId, int infoClass) {
        PointerByReference buffer = new PointerByReference();
        IntByReference bytes = new IntByReference();
        boolean ok = Wtsapi32.INSTANCE.WTSQuerySessionInformation(
                Wtsapi32.WTS_CURRENT_SERVER_HANDLE, sessionId, infoClass, buffer, bytes);
        if (!ok) return null;
        try {
            Pointer p = buffer.getValue();
            return p == null ? null : p.getWideString(0);
        } finally {
            if (buffer.getValue() != null) {
                Wtsapi32.INSTANCE.WTSFreeMemory(buffer.getValue());
            }
        }
    }

    // ─── 2. explorer.exe process owner ───────────────────────────────
    private static Resolved viaExplorerOwner() {
        try {
            String cmd = "Get-CimInstance Win32_Process -Filter \"Name='explorer.exe'\" | " +
                    "ForEach-Object { $o = Invoke-CimMethod -InputObject $_ -MethodName GetOwner; " +
                    "\"$($o.Domain)\\$($o.User)\" } | Select-Object -First 1";

            var r = ProcessExec.run(5,
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", cmd);

            if (!r.ok() || r.stdout().isBlank()) return null;
            String line = r.stdout().trim();
            int slash = line.indexOf('\\');
            if (slash <= 0) return null;
            String domain = line.substring(0, slash);
            String user   = line.substring(slash + 1);
            if (user.isBlank()) return null;

            log.info("AD user resolved via explorer.exe owner: {}\\{}", domain, user);
            return new Resolved(domain, user, "explorer-owner");
        } catch (Throwable t) {
            log.debug("explorer.exe owner resolution failed: {}", t.getMessage());
            return null;
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    // ─── JNA bindings ────────────────────────────────────────────────

    /** Kernel32 extension — WTSGetActiveConsoleSessionId lives here, not in jna-platform's Kernel32. */
    @SuppressWarnings("unused")
    public interface Kernel32Ext extends com.sun.jna.Library {
        Kernel32Ext INSTANCE = Native.load("kernel32", Kernel32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

        int WTSGetActiveConsoleSessionId();
    }

    @SuppressWarnings("unused")
    public interface Wtsapi32 extends com.sun.jna.Library {
        Wtsapi32 INSTANCE = Native.load("Wtsapi32", Wtsapi32.class, W32APIOptions.DEFAULT_OPTIONS);
        Pointer WTS_CURRENT_SERVER_HANDLE = null;

        boolean WTSQuerySessionInformation(
                Pointer hServer, int sessionId, int wtsInfoClass,
                PointerByReference ppBuffer, IntByReference pBytesReturned);

        void WTSFreeMemory(Pointer pointer);
    }
}