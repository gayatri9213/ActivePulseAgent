package com.activepulse.agent.monitor;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * WindowTracker — detects the currently focused application window.
 *
 * OS routing:
 *   Windows → JNA (User32 + Kernel32 QueryFullProcessImageNameW)
 *   macOS   → osascript (AppleScript via ProcessBuilder)
 *   Linux   → xdotool + /proc/<pid>/comm (via ProcessBuilder)
 */
public class WindowTracker {

    private static final Logger log = LoggerFactory.getLogger(WindowTracker.class);
    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    // ── Minimal Windows API extension ────────────────────────────────
    // QueryFullProcessImageNameW is the modern, reliable way to get
    // the full executable path from a process handle (Vista+).
    private interface ExtKernel32 extends StdCallLibrary {
        ExtKernel32 INSTANCE = Native.load("kernel32", ExtKernel32.class);
        boolean QueryFullProcessImageNameW(
                WinNT.HANDLE hProcess,
                int          dwFlags,
                char[]       lpExeName,
                IntByReference lpdwSize
        );
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    public WindowInfo getActiveWindow() {
        try {
            if (isWindows()) return getActiveWindowWindows();
            if (isMac())     return getActiveWindowMac();
            return getActiveWindowLinux();
        } catch (Exception e) {
            log.debug("getActiveWindow error: {}", e.getMessage());
            return WindowInfo.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Windows — JNA
    // ─────────────────────────────────────────────────────────────────

    private WindowInfo getActiveWindowWindows() {
        HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        if (hwnd == null) return WindowInfo.empty();

        // Window title
        char[] titleBuf = new char[512];
        User32.INSTANCE.GetWindowText(hwnd, titleBuf, titleBuf.length);
        String title = Native.toString(titleBuf).trim();

        // PID from window handle
        IntByReference pidRef = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
        int pid = pidRef.getValue();

        // Open process with PROCESS_QUERY_LIMITED_INFORMATION (0x1000)
        // — works even for elevated processes on modern Windows
        WinNT.HANDLE hProcess = Kernel32.INSTANCE.OpenProcess(0x1000, false, pid);

        String appName = "Unknown";
        if (hProcess != null) {
            try {
                char[] nameBuf  = new char[512];
                IntByReference sz = new IntByReference(nameBuf.length);
                boolean ok = ExtKernel32.INSTANCE.QueryFullProcessImageNameW(
                        hProcess, 0, nameBuf, sz);
                if (ok) {
                    String fullPath = new String(nameBuf, 0, sz.getValue()).trim();
                    appName = extractExeName(fullPath);
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(hProcess);
            }
        }

        return new WindowInfo(appName, title.isEmpty() ? appName : title);
    }

    /** Extracts "chrome" from "C:\Program Files\Google\Chrome\Application\chrome.exe" */
    private String extractExeName(String fullPath) {
        if (fullPath == null || fullPath.isBlank()) return "Unknown";
        String name = fullPath.contains("\\")
                ? fullPath.substring(fullPath.lastIndexOf('\\') + 1)
                : fullPath;
        return name.toLowerCase().endsWith(".exe")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    // ─────────────────────────────────────────────────────────────────
    //  macOS — AppleScript
    // ─────────────────────────────────────────────────────────────────

    private WindowInfo getActiveWindowMac() {
        String appName = exec("osascript", "-e",
                "tell application \"System Events\" to get name of first application process whose frontmost is true");

        String windowTitle = exec("osascript", "-e",
                "tell application \"System Events\" to get title of front window of " +
                        "(first application process whose frontmost is true)");

        return new WindowInfo(
                appName.isEmpty()      ? "Unknown" : appName,
                windowTitle.isEmpty()  ? appName   : windowTitle
        );
    }

    // ─────────────────────────────────────────────────────────────────
    //  Linux — xdotool + /proc
    // ─────────────────────────────────────────────────────────────────

    private WindowInfo getActiveWindowLinux() {
        String windowId = exec("xdotool", "getactivewindow");
        if (windowId.isEmpty()) return WindowInfo.empty();

        String title = exec("xdotool", "getwindowname", windowId.trim());
        String pid   = exec("xdotool", "getwindowpid",  windowId.trim());

        String appName = title;
        if (!pid.isEmpty()) {
            String comm = exec("cat", "/proc/" + pid.trim() + "/comm");
            if (!comm.isEmpty()) appName = comm;
        }

        return new WindowInfo(
                appName.isEmpty() ? "Unknown" : appName,
                title.isEmpty()   ? appName   : title
        );
    }

    // ─────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────

    private String exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                p.waitFor();
                return sb.toString().trim();
            }
        } catch (Exception e) {
            log.debug("exec({}) failed: {}", cmd[0], e.getMessage());
            return "";
        }
    }

    private boolean isWindows() { return OS.contains("win"); }
    private boolean isMac()     { return OS.contains("mac"); }
}
