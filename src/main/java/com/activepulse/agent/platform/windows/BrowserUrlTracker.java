package com.activepulse.agent.platform.windows;

import com.activepulse.agent.util.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Extracts the URL from the currently focused window for supported browsers
 * and PWA-style desktop apps that use a Chromium engine underneath.
 *
 * Strategy:
 *   1. Traditional browsers (chrome.exe, msedge.exe, etc.) have a visible
 *      address bar element (name = "Address and search bar" on Chrome/Edge).
 *      -> Strategy 1 in the PowerShell script.
 *
 *   2. Firefox has a differently-named Edit control.
 *      -> Strategy 2.
 *
 *   3. Some builds return the URL through an unnamed Edit control that just
 *      happens to hold an http* value (fallback).
 *      -> Strategy 3.
 *
 *   4. PWA apps (youtube.exe, youtubemusic.exe) hide the address bar entirely.
 *      But Chromium still exposes the current URL via the Document element's
 *      ValuePattern. Reading that gives us the URL.
 *      -> Strategy 4.
 *
 * BUG-FIX HISTORY:
 *   The embedded PowerShell script used $pid as the out-parameter name for
 *   GetWindowThreadProcessId. $pid is a READ-ONLY PowerShell automatic
 *   variable that holds the current PowerShell process's own PID. Because
 *   $ErrorActionPreference = SilentlyContinue was set at the top of the
 *   script, the failed assignment was swallowed and the script silently
 *   compared "powershell.exe" against the browser process name, always
 *   mismatching, and returned empty. Renamed to $targetPid.
 *
 * Performance:
 *   The PowerShell invocation costs ~200-500ms. To avoid running it on every
 *   window poll, results are cached per (processName + title) for CACHE_TTL_MS.
 *   The cache is invalidated automatically when the window title changes
 *   (i.e. user navigates to a new video / page).
 */
public final class BrowserUrlTracker {

    private static final Logger log = LoggerFactory.getLogger(BrowserUrlTracker.class);

    // --- Supported process sets --------------------------------------

    /** Traditional browsers with a visible address bar. */
    private static final Set<String> SUPPORTED_BROWSERS = Set.of(
            "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe",
            "opera.exe", "opera_gx.exe", "arc.exe", "vivaldi.exe",
            "chromium.exe", "iexplore.exe"
    );

    /**
     * PWA / Chromium-embedded desktop apps whose address bar is hidden but
     * whose Document element still exposes the URL. Strategy 4 handles these.
     */
    private static final Set<String> PWA_CAPABLE = Set.of(
            "youtube.exe",                    // Chrome/Edge YouTube PWA
            "youtubemusic.exe",                // Chrome/Edge YouTube Music PWA
            "ytdesktop.exe",                   // YouTube Desktop (3rd-party)
            "youtube-desktop.exe",             // variant
            "youtube-music.exe",               // 3rd-party YT Music
            "youtubemusicdesktopapp.exe"       // th-ch/youtube-music
    );

    /**
     * Per-browser address bar element name for Strategy 1. Empty string
     * means "no known bar name" (skip Strategy 1, jump to 2-4). PWAs
     * always get an empty bar name.
     */
    private static final Map<String, String> ADDRESS_BAR_NAME = Map.ofEntries(
            Map.entry("chrome.exe",   "Address and search bar"),
            Map.entry("msedge.exe",   "Address and search bar"),
            Map.entry("brave.exe",    "Address and search bar"),
            Map.entry("chromium.exe", "Address and search bar"),
            Map.entry("arc.exe",      "Address and search bar"),
            Map.entry("vivaldi.exe",  "Address and search bar"),
            Map.entry("opera.exe",    "Address field"),
            Map.entry("opera_gx.exe", "Address field"),
            Map.entry("firefox.exe",  ""),   // Strategy 2/3 handle Firefox
            Map.entry("iexplore.exe", "")    // Strategy 3 handles IE
    );

    // --- Cache -------------------------------------------------------

    private static final long CACHE_TTL_MS = 2_000;
    private volatile String  cachedProcess;
    private volatile String  cachedTitle;
    private volatile String  cachedUrl;
    private volatile long    cachedAtMs;

    // --- Script management -------------------------------------------

    private final Path scriptPath;
    private volatile boolean scriptWritten = false;

    private static volatile BrowserUrlTracker instance;

    private BrowserUrlTracker() {
        this.scriptPath = PathResolver.dataDir().resolve("get-url.ps1");
    }

    public static BrowserUrlTracker getInstance() {
        if (instance == null) {
            synchronized (BrowserUrlTracker.class) {
                if (instance == null) instance = new BrowserUrlTracker();
            }
        }
        return instance;
    }

    // --- Public API --------------------------------------------------

    public static boolean isBrowser(String processName) {
        if (processName == null || processName.isBlank()) return false;
        String lower = processName.trim().toLowerCase();
        return SUPPORTED_BROWSERS.contains(lower) || PWA_CAPABLE.contains(lower);
    }

    public String getUrl(String processName, String windowTitle) {
        if (!isBrowser(processName)) return "";
        String proc = processName.trim().toLowerCase();
        String title = windowTitle == null ? "" : windowTitle;

        long now = System.currentTimeMillis();
        if (cachedProcess != null
                && cachedProcess.equals(proc)
                && cachedTitle != null
                && cachedTitle.equals(title)
                && (now - cachedAtMs) < CACHE_TTL_MS) {
            return cachedUrl == null ? "" : cachedUrl;
        }

        try {
            ensureScriptWritten();
        } catch (IOException e) {
            log.warn("BrowserUrlTracker: cannot write PS script: {}", e.getMessage());
            return "";
        }

        String url = runScript(proc);

        cachedProcess = proc;
        cachedTitle   = title;
        cachedUrl     = url;
        cachedAtMs    = now;

        if (log.isDebugEnabled()) {
            String kind = PWA_CAPABLE.contains(proc) ? "PWA" : "browser";
            log.debug("BrowserUrlTracker: {} '{}' -> URL='{}'", kind, proc, url);
        }
        return url == null ? "" : url;
    }

    public String getUrl(String processName) {
        return getUrl(processName, "");
    }

    // --- API expected by WindowsActiveWindowTracker ------------------

    public static final class UrlResult {
        private final String url;

        public UrlResult(String url) {
            this.url = url == null ? "" : url;
        }

        public String url() { return url; }

        public boolean hasUrl() { return !url.isEmpty(); }

        @Override public String toString() { return "UrlResult[url=" + url + "]"; }
    }

    public UrlResult getActiveUrl(String processName) {
        String url = getUrl(processName, "");
        return new UrlResult(url);
    }

    // --- Script writing (embedded PS1) ------------------------------

    private synchronized void ensureScriptWritten() throws IOException {
        if (scriptWritten && Files.exists(scriptPath)) return;
        Files.createDirectories(scriptPath.getParent());
        Path tmp = scriptPath.resolveSibling("get-url.ps1.tmp");
        Files.writeString(tmp, PS_SCRIPT, StandardCharsets.US_ASCII);
        Files.move(tmp, scriptPath, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        scriptWritten = true;
        log.info("BrowserUrlTracker: wrote extraction script to {}", scriptPath);
    }

    private String runScript(String processName) {
        String barName = ADDRESS_BAR_NAME.getOrDefault(processName, "");

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptPath.toString(),
                "-ProcessName", processName,
                "-BarName", barName
        );
        pb.redirectErrorStream(true);

        Process proc = null;
        try {
            proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = proc.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("BrowserUrlTracker: PS script timeout for {}", processName);
                return "";
            }

            String result = out.toString().trim();
            if (result.startsWith("http://") || result.startsWith("https://")) {
                return result;
            }
            return "";
        } catch (IOException | InterruptedException e) {
            if (proc != null) proc.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("BrowserUrlTracker: PS invoke failed for {}: {}", processName, e.getMessage());
            return "";
        }
    }

    // --- Embedded PowerShell script ---------------------------------
    //
    // 4-strategy URL extraction. ASCII only. No backtick line-continuations.
    // Prints EXACTLY one line to stdout: the URL, or blank.
    //
    // IMPORTANT: variable naming — do NOT use $pid, $host, $home, $args,
    // $input, $matches, $psversiontable, or any other PowerShell automatic
    // variable. These are read-only and assignments silently fail under
    // SilentlyContinue, leading to hard-to-diagnose logic bugs.
    // For the target-process PID, we use $targetPid.

    private static final String PS_SCRIPT = String.join("\n",
            "param(",
            "    [Parameter(Mandatory=$true)][string]$ProcessName,",
            "    [string]$BarName = ''",
            ")",
            "",
            "$ErrorActionPreference = 'SilentlyContinue'",
            "",
            "try {",
            "    Add-Type -AssemblyName UIAutomationClient",
            "    Add-Type -AssemblyName UIAutomationTypes",
            "} catch { Write-Output ''; exit 0 }",
            "",
            "$AT  = [System.Windows.Automation.AutomationElement]",
            "$TS  = [System.Windows.Automation.TreeScope]",
            "$CTP = [System.Windows.Automation.ControlType]",
            "$VP  = [System.Windows.Automation.ValuePattern]",
            "",
            "$sig = @'",
            "[DllImport(\"user32.dll\")] public static extern IntPtr GetForegroundWindow();",
            "[DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);",
            "'@",
            "try { Add-Type -Name Win32Fg -Namespace ApWin -MemberDefinition $sig } catch { }",
            "",
            "$hwnd = [ApWin.Win32Fg]::GetForegroundWindow()",
            "",
            "# Do NOT rename this back to $pid - $pid is a read-only PowerShell automatic variable.",
            "$targetPid = 0",
            "[void][ApWin.Win32Fg]::GetWindowThreadProcessId($hwnd, [ref]$targetPid)",
            "",
            "if ($targetPid -eq 0) { Write-Output ''; exit 0 }",
            "",
            "try {",
            "    $procObj = Get-Process -Id $targetPid -ErrorAction Stop",
            "    $procExe = ($procObj.ProcessName + '.exe').ToLower()",
            "    if ($procExe -ne $ProcessName.ToLower()) {",
            "        Write-Output ''; exit 0",
            "    }",
            "} catch { Write-Output ''; exit 0 }",
            "",
            "$root = $AT::FromHandle($hwnd)",
            "if (-not $root) { Write-Output ''; exit 0 }",
            "",
            "function Get-EditValue($el) {",
            "    try {",
            "        $p = $el.GetCurrentPattern($VP::Pattern)",
            "        if ($p) { return $p.Current.Value }",
            "    } catch { }",
            "    return $null",
            "}",
            "",
            "$url = ''",
            "",
            "# --- Strategy 1: exact NameProperty match (Chrome/Edge/Brave/Opera) ----",
            "if ($BarName -and $BarName.Length -gt 0) {",
            "    try {",
            "        $cond = New-Object System.Windows.Automation.PropertyCondition(",
            "            $AT::NameProperty, $BarName)",
            "        $el = $root.FindFirst($TS::Descendants, $cond)",
            "        if ($el) {",
            "            $v = Get-EditValue $el",
            "            if ($v -and $v.Length -gt 0) {",
            "                if ($v -notmatch '^https?://') { $v = 'https://' + $v }",
            "                $url = $v",
            "            }",
            "        }",
            "    } catch { }",
            "}",
            "",
            "# --- Strategy 2: any Edit control with descriptive name (Firefox) -----",
            "if ($url -eq '') {",
            "    try {",
            "        $editCond = New-Object System.Windows.Automation.PropertyCondition(",
            "            $AT::ControlTypeProperty, $CTP::Edit)",
            "        $edits = $root.FindAll($TS::Descendants, $editCond)",
            "        foreach ($e in $edits) {",
            "            $n = ''",
            "            try { $n = $e.Current.Name } catch { }",
            "            if ($n) {",
            "                $nl = $n.ToLower()",
            "                if ($nl -match 'address|search|url|location') {",
            "                    $v = Get-EditValue $e",
            "                    if ($v -and $v.Length -gt 0) {",
            "                        if ($v -notmatch '^https?://') { $v = 'https://' + $v }",
            "                        $url = $v",
            "                        break",
            "                    }",
            "                }",
            "            }",
            "        }",
            "    } catch { }",
            "}",
            "",
            "# --- Strategy 3: any Edit control already holding http* value ---------",
            "if ($url -eq '') {",
            "    try {",
            "        $editCond = New-Object System.Windows.Automation.PropertyCondition(",
            "            $AT::ControlTypeProperty, $CTP::Edit)",
            "        $edits = $root.FindAll($TS::Descendants, $editCond)",
            "        foreach ($e in $edits) {",
            "            $v = Get-EditValue $e",
            "            if ($v -and $v -match '^https?://') {",
            "                $url = $v",
            "                break",
            "            }",
            "        }",
            "    } catch { }",
            "}",
            "",
            "# --- Strategy 4: Document element (PWA / hidden address bar) ---------",
            "if ($url -eq '') {",
            "    try {",
            "        $docCond = New-Object System.Windows.Automation.PropertyCondition(",
            "            $AT::ControlTypeProperty, $CTP::Document)",
            "        $docs = $root.FindAll($TS::Descendants, $docCond)",
            "        foreach ($d in $docs) {",
            "            $v = Get-EditValue $d",
            "            if ($v -and $v -match '^https?://') {",
            "                $url = $v",
            "                break",
            "            }",
            "            try {",
            "                $dn = $d.Current.Name",
            "                if ($dn -and $dn -match '^https?://') { $url = $dn; break }",
            "            } catch { }",
            "        }",
            "    } catch { }",
            "}",
            "",
            "Write-Output $url",
            "exit 0",
            ""
    );
}