package com.activepulse.agent.platform.windows;

import com.activepulse.agent.monitor.ActiveWindowInfo;
import com.activepulse.agent.monitor.ActiveWindowTracker;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Psapi;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Foreground window resolution with 4-layer fallback:
 *
 *   Attempt 1: OpenProcess(QUERY_LIMITED_INFORMATION | VM_READ)
 *              + GetModuleFileNameExW    — fast, works for most user processes
 *   Attempt 2: OpenProcess(QUERY_LIMITED_INFORMATION)
 *              + QueryFullProcessImageName — works for some protected apps
 *   Attempt 3: Toolhelp32 snapshot        — iterates ALL processes from the
 *              kernel table without opening them. Works for lsass, MsMpEng,
 *              csrss, services.exe, and other Protected Process Light apps.
 *   Attempt 4: GetClassName(hwnd)         — window class fallback. Not the
 *              exe name, but tells us "this is a UWP shell", "Windows Desktop",
 *              "taskbar", etc. Only used when 1-3 all fail.
 *
 * If all four fail, we return null and let ActivitySessionManager.safeProcessName
 * substitute "System Process".
 */
public final class WindowsActiveWindowTracker implements ActiveWindowTracker {

    private static final Logger log = LoggerFactory.getLogger(WindowsActiveWindowTracker.class);
    private static final int PROCESS_FLAGS = 0x1000 | 0x0010; // QUERY_LIMITED_INFORMATION | VM_READ

    // Cache last URL per (process, title) for 2 seconds to avoid spawning PowerShell
    // on every 5-second activity poll.
    private static final long CACHE_TTL_MS = 2_000;
    private volatile String cacheKey;
    private volatile String cacheUrl;
    private volatile long   cacheTimestamp;

    // ================================================================
    // Toolhelp32 native bindings (Strategy 5)
    // ================================================================

    /** CreateToolhelp32Snapshot flags. */
    private static final int TH32CS_SNAPPROCESS = 0x00000002;

    /**
     * PROCESSENTRY32W — the wide-string variant. JNA maps this to a Structure.
     * We call Process32FirstW / Process32NextW to walk it.
     */
    public static class PROCESSENTRY32W extends Structure {
        public int    dwSize;
        public int    cntUsage;
        public int    th32ProcessID;
        public com.sun.jna.Pointer th32DefaultHeapID; // ULONG_PTR
        public int    th32ModuleID;
        public int    cntThreads;
        public int    th32ParentProcessID;
        public int    pcPriClassBase;
        public int    dwFlags;
        public char[] szExeFile = new char[260]; // MAX_PATH

        public PROCESSENTRY32W() {
            dwSize = size();
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID",
                    "th32ModuleID", "cntThreads", "th32ParentProcessID",
                    "pcPriClassBase", "dwFlags", "szExeFile"
            );
        }

        public String getExeFile() {
            int len = 0;
            while (len < szExeFile.length && szExeFile[len] != '\0') len++;
            return new String(szExeFile, 0, len);
        }
    }

    /** JNA proxy for the small handful of Toolhelp32 functions we need. */
    public interface Toolhelp32 extends StdCallLibrary {
        Toolhelp32 INSTANCE = Native.load("kernel32", Toolhelp32.class);

        HANDLE CreateToolhelp32Snapshot(DWORD dwFlags, DWORD th32ProcessID);
        boolean Process32FirstW(HANDLE hSnapshot, PROCESSENTRY32W lppe);
        boolean Process32NextW(HANDLE hSnapshot, PROCESSENTRY32W lppe);
        boolean CloseHandle(HANDLE hObject);
    }

    // ================================================================
    // Main entry point
    // ================================================================

    @Override
    public ActiveWindowInfo getActiveWindow() {
        try {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return ActiveWindowInfo.unknown();

            // Title
            char[] buf = new char[1024];
            int len = User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
            String title = (len > 0) ? new String(buf, 0, len) : "";

            // PID
            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();

            String processName = null;
            if (pid > 0) {
                processName = resolveProcessName(pid, hwnd, title);
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

    // ================================================================
    // Process name resolution — 4 attempts
    // ================================================================

    /**
     * Resolve exe name using the 4-attempt fallback chain.
     * Returns null only if everything fails; caller substitutes System Process.
     */
    private String resolveProcessName(int pid, HWND hwnd, String title) {
        // ── Attempt 1: OpenProcess + GetModuleFileNameExW ────────────
        String name = tryOpenProcessWithModuleName(pid);
        if (name != null) return name;

        // ── Attempt 2: OpenProcess + QueryFullProcessImageName ───────
        name = tryOpenProcessWithImageName(pid);
        if (name != null) return name;

        // ── Attempt 3: Toolhelp32 snapshot ───────────────────────────
        // Works for lsass.exe, MsMpEng.exe, csrss.exe, and other PPL processes
        // because it reads the kernel process table without opening handles.
        name = tryToolhelp32(pid);
        if (name != null) {
            log.debug("Resolved pid={} via Toolhelp32: {}", pid, name);
            return name;
        }

        // ── Attempt 4: GetClassName(hwnd) ────────────────────────────
        // Not an exe name — a Windows-registered class identifier. Useful for
        // UWP apps, Windows shell parts, taskbar, desktop, etc.
        name = tryClassName(hwnd);
        if (name != null) {
            log.debug("Resolved pid={} via ClassName: {}", pid, name);
            return name;
        }

        // All attempts failed. Caller will show "System Process".
        log.debug("Could not resolve process name for pid={} title='{}' after all 4 attempts",
                pid, title);
        return null;
    }

    // ── Attempt 1 ─────────────────────────────────────────────────────
    private String tryOpenProcessWithModuleName(int pid) {
        HANDLE h = Kernel32.INSTANCE.OpenProcess(PROCESS_FLAGS, false, pid);
        if (h == null) return null;
        try {
            char[] path = new char[1024];
            int plen = Psapi.INSTANCE.GetModuleFileNameExW(h, null, path, path.length);
            if (plen > 0) {
                String full = new String(path, 0, plen);
                return Paths.get(full).getFileName().toString();
            }
            return null;
        } catch (Throwable t) {
            log.debug("Attempt 1 (GetModuleFileNameExW) failed for pid={}: {}", pid, t.getMessage());
            return null;
        } finally {
            Kernel32.INSTANCE.CloseHandle(h);
        }
    }

    // ── Attempt 2 ─────────────────────────────────────────────────────
    private String tryOpenProcessWithImageName(int pid) {
        HANDLE h = Kernel32.INSTANCE.OpenProcess(0x1000, false, pid); // QUERY_LIMITED only
        if (h == null) return null;
        try {
            char[] path = new char[1024];
            IntByReference size = new IntByReference(path.length);
            if (Kernel32.INSTANCE.QueryFullProcessImageName(h, 0, path, size)) {
                String full = new String(path, 0, size.getValue());
                return Paths.get(full).getFileName().toString();
            }
            return null;
        } catch (Throwable t) {
            log.debug("Attempt 2 (QueryFullProcessImageName) failed for pid={}: {}",
                    pid, t.getMessage());
            return null;
        } finally {
            Kernel32.INSTANCE.CloseHandle(h);
        }
    }

    // ── Attempt 3 ─────────────────────────────────────────────────────
    /**
     * Walk the kernel process table via CreateToolhelp32Snapshot.
     * No handle to the target process is opened — this works for PPL/protected
     * processes that reject OpenProcess.
     */
    private String tryToolhelp32(int pid) {
        HANDLE snap = null;
        try {
            snap = Toolhelp32.INSTANCE.CreateToolhelp32Snapshot(
                    new DWORD(TH32CS_SNAPPROCESS), new DWORD(0));
            if (snap == null || snap.equals(new HANDLE(com.sun.jna.Pointer.createConstant(-1)))) {
                return null;
            }

            PROCESSENTRY32W entry = new PROCESSENTRY32W();
            if (!Toolhelp32.INSTANCE.Process32FirstW(snap, entry)) {
                return null;
            }

            do {
                if (entry.th32ProcessID == pid) {
                    String exe = entry.getExeFile();
                    if (exe != null && !exe.isBlank()) {
                        return exe;
                    }
                    return null;
                }
            } while (Toolhelp32.INSTANCE.Process32NextW(snap, entry));

            return null;
        } catch (Throwable t) {
            log.debug("Attempt 3 (Toolhelp32) failed for pid={}: {}", pid, t.getMessage());
            return null;
        } finally {
            if (snap != null) {
                try { Toolhelp32.INSTANCE.CloseHandle(snap); } catch (Throwable ignored) {}
            }
        }
    }

    // ── Attempt 4 ─────────────────────────────────────────────────────
    /**
     * Read the window's registered class name. Some known-good mappings:
     *   Windows.UI.Core.CoreWindow    -> UWP app window
     *   ApplicationFrameWindow        -> UWP shell frame
     *   Progman                       -> Windows Desktop
     *   Shell_TrayWnd                 -> Taskbar
     *   WorkerW                       -> Desktop background worker
     *   Windows.UI.Input.InputSite.WindowClass -> UWP input host
     *
     * We prefix with "class:" so downstream code and reports can tell this
     * is a fallback identifier, not a real exe.
     */
    private String tryClassName(HWND hwnd) {
        try {
            char[] buf = new char[256];
            int n = User32.INSTANCE.GetClassName(hwnd, buf, buf.length);
            if (n <= 0) return null;
            String cls = new String(buf, 0, n).trim();
            if (cls.isEmpty()) return null;
            return "class:" + cls;
        } catch (Throwable t) {
            log.debug("Attempt 4 (GetClassName) failed: {}", t.getMessage());
            return null;
        }
    }

    // ================================================================
    // URL cache
    // ================================================================

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