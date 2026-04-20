package com.activepulse.agent.monitor;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * BrowserUrlTracker — reads active URL from all major browsers.
 *
 * Supported:
 *   Chrome, Edge, Brave, Opera, Vivaldi  → "Address and search bar"
 *   Firefox                              → "Search or enter address"
 *   Internet Explorer / legacy Edge      → window title parsing
 *
 * Uses PowerShell UIAutomation via a .ps1 file (no Chrome flags needed).
 * Script is written once to ~/.activepulse/get-url.ps1 on first use.
 */
public class BrowserUrlTracker {

    private static final Logger log = LoggerFactory.getLogger(BrowserUrlTracker.class);

    // Process name → address bar automation Name
    private static final Map<String, String> BROWSER_BAR = Map.of(
            "chrome",   "Address and search bar",
            "msedge",   "Address and search bar",
            "brave",    "Address and search bar",
            "opera",    "Address and search bar",
            "vivaldi",  "Address and search bar",
            "firefox",  "Search or enter address",
            "iexplore", "Address and search bar"
    );

    // Firefox needs a different automation approach —
    // its address bar Name is not "Search or enter address" in all versions.
    // We try the bar Name first, then fall back to ControlType + editable check.
    private static final Set<String> FIREFOX_PROCS = Set.of("firefox");
    private static final Set<String> SUPPORTED = BROWSER_BAR.keySet();

    private static final Path PS_SCRIPT = Paths.get(
            System.getProperty("user.home"), ".activepulse", "get-url.ps1");

    private boolean scriptReady = false;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile BrowserUrlTracker instance;
    private BrowserUrlTracker() { writePsScript(); }

    public static BrowserUrlTracker getInstance() {
        if (instance == null) {
            synchronized (BrowserUrlTracker.class) {
                if (instance == null) instance = new BrowserUrlTracker();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Write PowerShell script
    // ─────────────────────────────────────────────────────────────────

    private void writePsScript() {
        // Firefox needs special handling — try Name property first,
        // then fall back to finding the first editable text field
        // in the toolbar (ControlType = Edit in the navigation bar)
        String script = """
            param($procName, $barName)
            Add-Type -AssemblyName UIAutomationClient
            Add-Type -AssemblyName UIAutomationTypes
            try {
                $proc = Get-Process $procName -ErrorAction SilentlyContinue `
                        | Where-Object { $_.MainWindowHandle -ne 0 } `
                        | Select-Object -First 1
                if (-not $proc) { exit }

                $ae = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
                if (-not $ae) { exit }

                # Strategy 1: find by Name property (Chrome/Edge/Brave)
                $cond = New-Object System.Windows.Automation.PropertyCondition(
                    [System.Windows.Automation.AutomationElement]::NameProperty, $barName)
                $bar = $ae.FindFirst(
                    [System.Windows.Automation.TreeScope]::Descendants, $cond)

                # Strategy 2: Firefox fallback — find Edit control in toolbar
                if (-not $bar) {
                    $editCond = New-Object System.Windows.Automation.PropertyCondition(
                        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
                        [System.Windows.Automation.ControlType]::Edit)
                    $edits = $ae.FindAll(
                        [System.Windows.Automation.TreeScope]::Descendants, $editCond)
                    foreach ($edit in $edits) {
                        $name = $edit.Current.Name
                        if ($name -like "*address*" -or $name -like "*search*" -or $name -like "*url*") {
                            $bar = $edit
                            break
                        }
                    }
                    # Strategy 3: take first Edit that has http value
                    if (-not $bar) {
                        foreach ($edit in $edits) {
                            try {
                                $vp  = [System.Windows.Automation.ValuePattern]::Pattern
                                $val = $edit.GetCurrentPattern($vp).Current.Value
                                if ($val -like "http*") { $bar = $edit; break }
                            } catch {}
                        }
                    }
                }

                if ($bar) {
                    $vp  = [System.Windows.Automation.ValuePattern]::Pattern
                    $val = $bar.GetCurrentPattern($vp).Current.Value
                    if ($val) { Write-Output $val }
                }
            } catch {
                # silent exit
            }
            """;
        try {
            Files.createDirectories(PS_SCRIPT.getParent());
            Files.writeString(PS_SCRIPT, script);
            scriptReady = true;
            log.info("BrowserUrlTracker: PS script ready — {}", PS_SCRIPT);
        } catch (Exception e) {
            log.error("Failed to write PS script: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    public UrlResult getActiveUrl(String appName) {
        if (!scriptReady || appName == null) return null;

        String proc = appName.toLowerCase()
                .replace(".exe", "")
                .trim();

        if (!SUPPORTED.contains(proc)) return null;

        // Only read when browser window is in foreground
        HWND fg = User32.INSTANCE.GetForegroundWindow();
        if (fg == null) return null;

        String barName = BROWSER_BAR.get(proc);
        String raw     = runScript(proc, barName);

        log.debug("URL raw [{}]: '{}'", proc, raw);
        if (raw == null || raw.isBlank()) return null;

        return buildResult(raw);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Run the .ps1 file
    // ─────────────────────────────────────────────────────────────────

    private String runScript(String procName, String barName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NonInteractive",
                    "-NoProfile",
                    "-ExecutionPolicy", "Bypass",
                    "-File",  PS_SCRIPT.toAbsolutePath().toString(),
                    procName,
                    barName
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();

            String output;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                output = br.lines()
                        .map(String::trim)
                        .filter(l -> !l.isBlank())
                        .findFirst()
                        .orElse(null);
            }

            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return null; }

            return output;
        } catch (Exception e) {
            log.debug("PS script exec failed: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Build UrlResult
    // ─────────────────────────────────────────────────────────────────

    private UrlResult buildResult(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // Skip internal browser pages
        if (raw.startsWith("chrome://") || raw.startsWith("edge://")
                || raw.startsWith("about:") || raw.startsWith("browser://")
                || raw.equalsIgnoreCase("Search or enter address")
                || raw.equalsIgnoreCase("Search or type URL")
                || raw.equalsIgnoreCase("Address and search bar")) {
            return null;
        }

        String url = raw;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        try {
            URI    uri    = URI.create(url);
            String host   = uri.getHost();
            if (host == null || host.isBlank()) return null;
            String domain = host.startsWith("www.") ? host.substring(4) : host;
            return new UrlResult(url, domain, raw);
        } catch (Exception e) {
            log.debug("URL parse failed '{}': {}", raw, e.getMessage());
            return null;
        }
    }

    public record UrlResult(String url, String domain, String rawText) {}
}