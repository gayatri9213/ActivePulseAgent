package com.activepulse.agent.platform.windows;

import com.activepulse.agent.monitor.ActiveWindowInfo;
import com.activepulse.agent.monitor.ActiveWindowTracker;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Psapi;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

public final class WindowsActiveWindowTracker implements ActiveWindowTracker {

    private static final Logger log = LoggerFactory.getLogger(WindowsActiveWindowTracker.class);
    private static final int PROCESS_FLAGS = 0x1000 | 0x0010; // QUERY_LIMITED_INFORMATION | VM_READ

    // Cache last URL per (process, title) for 2 seconds to avoid spawning PowerShell
    // on every 5-second activity poll.
    private static final long CACHE_TTL_MS = 2_000;
    private volatile String cacheKey;
    private volatile String cacheUrl;
    private volatile long   cacheTimestamp;

    @Override
    public ActiveWindowInfo getActiveWindow() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return ActiveWindowInfo.unknown();

            // Title
            char[] buf = new char[1024];
            int len = User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
            String title = (len > 0) ? new String(buf, 0, len) : "";

            // Process
            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();

            String processName = null;
            if (pid > 0) {
                // Attempt 1: OpenProcess + GetModuleFileNameExW (needs read access)
                HANDLE h = Kernel32.INSTANCE.OpenProcess(PROCESS_FLAGS, false, pid);
                if (h != null) {
                    try {
                        char[] path = new char[1024];
                        int plen = Psapi.INSTANCE.GetModuleFileNameExW(h, null, path, path.length);
                        if (plen > 0) {
                            String full = new String(path, 0, plen);
                            processName = Paths.get(full).getFileName().toString();
                        }
                    } finally {
                        Kernel32.INSTANCE.CloseHandle(h);
                    }
                }

                // Attempt 2: QueryFullProcessImageName (needs only PROCESS_QUERY_LIMITED_INFORMATION)
                // Works when OpenProcess with VM_READ was denied (protected processes)
                if (processName == null) {
                    HANDLE h2 = Kernel32.INSTANCE.OpenProcess(0x1000, false, pid); // QUERY_LIMITED_INFORMATION only
                    if (h2 != null) {
                        try {
                            char[] path = new char[1024];
                            IntByReference size = new IntByReference(path.length);
                            if (Kernel32.INSTANCE.QueryFullProcessImageName(h2, 0, path, size)) {
                                String full = new String(path, 0, size.getValue());
                                processName = Paths.get(full).getFileName().toString();
                            }
                        } catch (Throwable t) {
                            log.debug("QueryFullProcessImageName failed for pid {}: {}", pid, t.getMessage());
                        } finally {
                            Kernel32.INSTANCE.CloseHandle(h2);
                        }
                    }
                }

                // Attempt 3: log the failure with a helpful marker
                if (processName == null) {
                    log.debug("Could not resolve process name for pid={} title='{}' " +
                            "(protected/elevated process — access denied)", pid, title);
                }
            }

            // URL — only for browsers, with 2s cache
            String url = "";
            if (BrowserUrlTracker.isBrowser(processName)) {
                url = resolveUrlCached(processName, title);
            }

            return new ActiveWindowInfo(processName, title, url);
        } catch (Throwable t) {
            log.debug("Windows active window read failed: {}", t.getMessage());
            return ActiveWindowInfo.unknown();
        }
    }

    private String resolveUrlCached(String processName, String title) {
        String key = processName + "|" + (title == null ? "" : title);
        long now = System.currentTimeMillis();

        if (key.equals(cacheKey) && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cacheUrl == null ? "" : cacheUrl;
        }

        BrowserUrlTracker.UrlResult result = BrowserUrlTracker.getInstance().getActiveUrl(processName);
        String url = (result == null) ? "" : result.url();

        cacheKey       = key;
        cacheUrl       = url;
        cacheTimestamp = now;
        return url;
    }
}