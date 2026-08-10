# ═══════════════════════════════════════════════════════════════════
# ActivePulse — Scratch Start Installation Script (v1.0.0)
#
# USAGE:
#   1. Save as: install-activepulse-v1.0.0.ps1
#   2. Right-click PowerShell -> Run as administrator (enter admin password)
#   3. cd to the folder containing this file
#   4. If needed:  Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
#   5. Run:        .\install-activepulse-v1.0.0.ps1
#
# What it does:
#   1.  Kills ALL ActivePulse/java processes from ALL users
#   2.  Uninstalls any existing ActivePulse version
#   3.  Cleans up data folders from ADMIN accounts only
#   4.  Removes stale lock files everywhere
#   5.  Finds the MSI (searches all user Downloads folders)
#   6.  Stages MSI to neutral C:\Temp location
#   7.  Installs silently to C:\Program Files\ActivePulse
#   8.  Verifies install
#   9.  Configures HKLM\Run autostart (persists across restarts)
#   10. Launches agent NOW as the DESKTOP USER (not as admin)
#   11. Prints summary
#
# The key trick in Step 10: uses `schtasks` to spawn the agent under
# the logged-in user's session with their non-admin token, so the agent
# runs as the actual desktop user (e.g. gaytri.sonar) not as admin.
# ═══════════════════════════════════════════════════════════════════

# ─── Configuration ─────────────────────────────────────────────────
$ADMIN_USERS = @(
    "adcadmin",
    "administrator",
    "Administrator",
    "SYSTEM",
    "DefaultAccount",
    "WDAGUtilityAccount",
    "admin",
    "user"
)
$INSTALL_DIR = "C:\Program Files\ActivePulse"
# ───────────────────────────────────────────────────────────────────

# Verify running as Admin
$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole( `
    [Security.Principal.WindowsBuiltInRole] "Administrator")
if (-not $isAdmin) {
    Write-Host "ERROR: Must run as Administrator." -ForegroundColor Red
    Write-Host "Right-click PowerShell -> Run as administrator" -ForegroundColor Yellow
    return
}

Write-Host ""
Write-Host "===============================================================" -ForegroundColor Cyan
Write-Host "  ActivePulse v1.0.0 - Scratch Install" -ForegroundColor Cyan
Write-Host "===============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Running as:  $(whoami)"
Write-Host "  Machine:     $env:COMPUTERNAME"
Write-Host "  Install dir: $INSTALL_DIR"
Write-Host ""

# ═══════════════════════════════════════════════════════════════════
# STEP 1: Kill ALL ActivePulse processes
# ═══════════════════════════════════════════════════════════════════
Write-Host "[1/11] Killing all ActivePulse processes (across all users)..." -ForegroundColor Yellow
$killed = 0
Get-WmiObject Win32_Process -Filter "Name='ActivePulse.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    $owner = $_.GetOwner()
    Write-Host "       Killing ActivePulse PID $($_.ProcessId)  ($($owner.Domain)\$($owner.User))"
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    $killed++
}
Get-WmiObject Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -and ($_.CommandLine -like "*activepulse*" -or $_.CommandLine -like "*ActivePulse*")) {
        $owner = $_.GetOwner()
        Write-Host "       Killing java PID $($_.ProcessId)  ($($owner.Domain)\$($owner.User))"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        $killed++
    }
}
Start-Sleep -Seconds 3
Write-Host "       Killed $killed process(es)" -ForegroundColor Green

# ═══════════════════════════════════════════════════════════════════
# STEP 2: Uninstall any existing version
# ═══════════════════════════════════════════════════════════════════
Write-Host "[2/11] Uninstalling existing ActivePulse..." -ForegroundColor Yellow
$existing = Get-WmiObject Win32_Product -Filter "Name LIKE '%ActivePulse%'" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($existing) {
    Write-Host "       Found: $($existing.Name) $($existing.Version)"
    $uninstallArgs = @("/x", $existing.IdentifyingNumber, "/quiet", "/norestart")
    Start-Process msiexec.exe -ArgumentList $uninstallArgs -Wait
    Start-Sleep -Seconds 20
    Write-Host "       Uninstalled" -ForegroundColor Green
} else {
    Write-Host "       Nothing to uninstall" -ForegroundColor Green
}

# ═══════════════════════════════════════════════════════════════════
# STEP 3: Clean admin user data
# ═══════════════════════════════════════════════════════════════════
Write-Host "[3/11] Cleaning admin user ActivePulse data..." -ForegroundColor Yellow
foreach ($adminUser in $ADMIN_USERS) {
    $userDir = "C:\Users\$adminUser"
    if (-not (Test-Path $userDir)) { continue }
    foreach ($subPath in @("AppData\Local\ActivePulse", ".activepulse")) {
        $full = Join-Path $userDir $subPath
        if (Test-Path $full) {
            try {
                Remove-Item $full -Recurse -Force -ErrorAction Stop
                Write-Host "       Removed: $full" -ForegroundColor Green
            } catch {
                Write-Host "       Could not remove ${full}: $($_.Exception.Message)" -ForegroundColor Red
            }
        }
    }
}
Remove-Item "$env:TEMP\activepulse-skip.log"    -Force -ErrorAction SilentlyContinue
Remove-Item "$env:TEMP\activepulse-stdout.log"  -Force -ErrorAction SilentlyContinue
Remove-Item "$env:TEMP\activepulse-stderr.log"  -Force -ErrorAction SilentlyContinue

# ═══════════════════════════════════════════════════════════════════
# STEP 4: Clean lock files
# ═══════════════════════════════════════════════════════════════════
Write-Host "[4/11] Cleaning stale lock files (all users)..." -ForegroundColor Yellow
$lockCount = 0
Get-ChildItem "C:\Users" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    foreach ($lockName in @("activepulse.lock", "watchdog.lock")) {
        $lockPath = "$($_.FullName)\AppData\Local\ActivePulse\$lockName"
        if (Test-Path $lockPath) {
            Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
            Write-Host "       Removed: $lockPath" -ForegroundColor Gray
            $lockCount++
        }
    }
}
Write-Host "       Cleaned $lockCount lock file(s)" -ForegroundColor Green

# ═══════════════════════════════════════════════════════════════════
# STEP 5: Find MSI
# ═══════════════════════════════════════════════════════════════════
Write-Host "[5/11] Locating ActivePulse MSI..." -ForegroundColor Yellow
$msi = $null
$candidates = Get-ChildItem "C:\Users" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $dl = "$($_.FullName)\Downloads"
    if (Test-Path $dl) {
        Get-ChildItem $dl -Filter "ActivePulse*.msi" -Recurse -ErrorAction SilentlyContinue
    }
} | Sort-Object LastWriteTime -Descending

if ($candidates) {
    $msi = $candidates[0].FullName
    Write-Host "       Found: $msi" -ForegroundColor Green
    Write-Host "       Size:  $([math]::Round($candidates[0].Length / 1MB, 1)) MB"
    Write-Host "       Date:  $($candidates[0].LastWriteTime)"
}

# Also search the script's own folder and a local dist\ folder (jpackage output)
if (-not $msi) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $localCandidates = @()
    foreach ($d in @($scriptDir, (Join-Path $scriptDir "dist"), "C:\Temp", "C:\Installers")) {
        if ($d -and (Test-Path $d)) {
            $localCandidates += Get-ChildItem $d -Filter "ActivePulse*.msi" -ErrorAction SilentlyContinue
        }
    }
    $localCandidates = $localCandidates | Sort-Object LastWriteTime -Descending
    if ($localCandidates) {
        $msi = $localCandidates[0].FullName
        Write-Host "       Found (local): $msi" -ForegroundColor Green
    }
}

if (-not $msi) {
    @("C:\Temp\ActivePulse-1.0.0.msi", "C:\Temp\ActivePulse-Install.msi", "C:\Installers\ActivePulse-1.0.0.msi") | ForEach-Object {
        if (-not $msi -and (Test-Path $_)) { $msi = $_ }
    }
}

if (-not $msi) {
    Write-Host "       ERROR: MSI not found anywhere. Download to C:\Temp\ first." -ForegroundColor Red
    return
}

# ═══════════════════════════════════════════════════════════════════
# STEP 6: Stage MSI
# ═══════════════════════════════════════════════════════════════════
Write-Host "[6/11] Staging MSI in C:\Temp..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path "C:\Temp" | Out-Null
$stagedMsi = "C:\Temp\ActivePulse-Install.msi"
Copy-Item $msi -Destination $stagedMsi -Force
Write-Host "       Staged at: $stagedMsi" -ForegroundColor Green

# ═══════════════════════════════════════════════════════════════════
# STEP 7: Install MSI
# ═══════════════════════════════════════════════════════════════════
Write-Host "[7/11] Installing MSI silently to $INSTALL_DIR..." -ForegroundColor Yellow
$installLog = "$env:TEMP\activepulse_install.log"

# Primary attempt: with INSTALLDIR (works on classic MSI).
$msiArgs = @(
    "/i", "`"$stagedMsi`"", "/quiet", "/norestart",
    "REBOOT=ReallySuppress",
    "INSTALLDIR=`"$INSTALL_DIR`"",
    "ALLUSERS=1", "MSIINSTALLPERUSER=0",
    "/l*v", "`"$installLog`""
)

$proc = Start-Process msiexec.exe -ArgumentList $msiArgs -Wait -PassThru -NoNewWindow
$exitCode = $proc.ExitCode
Write-Host "       Exit code: $exitCode"

# jpackage MSIs may reject INSTALLDIR (1620/1603). Retry WITHOUT it — jpackage
# installs to its own default C:\Program Files\ActivePulse anyway.
if ($exitCode -ne 0 -and $exitCode -ne 3010) {
    Write-Host "       Install with INSTALLDIR failed ($exitCode). Retrying without INSTALLDIR..." -ForegroundColor Yellow
    $msiArgs2 = @(
        "/i", "`"$stagedMsi`"", "/quiet", "/norestart",
        "REBOOT=ReallySuppress",
        "ALLUSERS=1", "MSIINSTALLPERUSER=0",
        "/l*v", "`"$installLog`""
    )
    $proc = Start-Process msiexec.exe -ArgumentList $msiArgs2 -Wait -PassThru -NoNewWindow
    $exitCode = $proc.ExitCode
    Write-Host "       Retry exit code: $exitCode"
}

switch ($exitCode) {
    0     { Write-Host "       OK: Install succeeded" -ForegroundColor Green }
    1603  { Write-Host "       ERROR 1603: Fatal install error" -ForegroundColor Red }
    1605  { Write-Host "       ERROR 1605: Already installed" -ForegroundColor Red }
    1618  { Write-Host "       ERROR 1618: Another install running" -ForegroundColor Red }
    3010  { Write-Host "       OK: Installed (reboot suppressed)" -ForegroundColor Green }
    default { Write-Host "       Unexpected exit: $exitCode" -ForegroundColor Red }
}

if ($exitCode -ne 0 -and $exitCode -ne 3010) {
    Write-Host ""
    Write-Host "       Last 30 lines of install log:" -ForegroundColor Yellow
    Get-Content $installLog -Tail 30
    return
}

Start-Sleep -Seconds 5

# ═══════════════════════════════════════════════════════════════════
# STEP 8: Verify install
# ═══════════════════════════════════════════════════════════════════
Write-Host "[8/11] Verifying install..." -ForegroundColor Yellow
$exe = "$INSTALL_DIR\ActivePulse.exe"

if (Test-Path $exe) {
    Write-Host "       OK: $exe" -ForegroundColor Green
} else {
    Write-Host "       ERROR: $exe not found!" -ForegroundColor Red
    return
}

$reg = Get-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*" |
    Where-Object { $_.DisplayName -eq "ActivePulse" } | Select-Object -First 1
if ($reg) {
    Write-Host "       Registered: $($reg.DisplayName) $($reg.DisplayVersion)" -ForegroundColor Green
}

# ═══════════════════════════════════════════════════════════════════
# STEP 9: Capture install-time location (admin-safe: private IP + ip-api)
# ═══════════════════════════════════════════════════════════════════
# LOCATION CAPTURE START
# Windows Location is NOT captured here - consent is per-user and this runs as
# admin (always Denied). The running agent captures Windows Location at runtime
# in the user's session. Here we capture only what works in admin context.
Write-Host "[9/11] Capturing install-time location..." -ForegroundColor Yellow

# agent.env in the INSTALL DIR (machine-wide, readable by all users)
$envFile = "$INSTALL_DIR\agent.env"

# Office subnet -> city map. KEEP IN SYNC with OFFICE_*_SUBNETS in agent.env.
$officeMap = @(
    @{ Prefix="192.168.30.";  City="Pune";   Region="Maharashtra"; Lat=18.511033; Lng=73.925595 }
    @{ Prefix="192.168.210."; City="Nashik"; Region="Maharashtra"; Lat=19.9433;   Lng=73.7265 }
    @{ Prefix="192.168.137."; City="Nashik"; Region="Maharashtra"; Lat=19.9433;   Lng=73.7265 }
    @{ Prefix="192.168.8.";   City="Nashik"; Region="Maharashtra"; Lat=19.9433;   Lng=73.7265 }
    @{ Prefix="192.168.9.";   City="Nashik"; Region="Maharashtra"; Lat=19.9433;   Lng=73.7265 }
    @{ Prefix="192.168.70.";  City="Pune";   Region="Maharashtra"; Lat=18.511033; Lng=73.925595 }
    @{ Prefix="192.168.60.";  City="Pune";     Region="Maharashtra"; Lat=18.511033; Lng=73.925595 }
)

$privateIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.PrefixOrigin -ne "WellKnown" } |
    Select-Object -First 1).IPAddress

$aCity=""; $aRegion=""; $aCountry=""; $aLat=""; $aLng=""; $source="UNRESOLVED"; $publicIp=""

# Tier 1: office subnet (EXACT)
if ($privateIp) {
    Write-Host "       Private IP: $privateIp"
    foreach ($o in $officeMap) {
        if ($privateIp.StartsWith($o.Prefix)) {
            $aCity=$o.City; $aRegion=$o.Region; $aCountry="India"; $aLat=$o.Lat; $aLng=$o.Lng
            $source="OFFICE_EXACT"
            Write-Host "       Office subnet match: $aCity (EXACT)" -ForegroundColor Green
            break
        }
    }
}

# Tier 2: ip-api.com (APPROX, ISP-level) only if no office match
if ($source -eq "UNRESOLVED") {
    try {
        $geo = Invoke-RestMethod -Uri "http://ip-api.com/json/?fields=status,city,regionName,country,lat,lon,query" -TimeoutSec 8
        if ($geo.status -eq "success") {
            $publicIp=$geo.query; $aCity=$geo.city; $aRegion=$geo.regionName
            $aCountry=$geo.country; $aLat=$geo.lat; $aLng=$geo.lon; $source="IP_APPROX"
            Write-Host "       ip-api: $aCity (APPROX, ISP-level)" -ForegroundColor DarkYellow
        }
    } catch { Write-Host "       ip-api lookup failed: $($_.Exception.Message)" -ForegroundColor DarkYellow }
}

if ($source -eq "UNRESOLVED") {
    Write-Host "       No location resolved - agent will detect at runtime." -ForegroundColor DarkYellow
}

# Rewrite ASSIGNED_*/INSTALL_* lines in agent.env (idempotent: strip old, add new)
$managedKeys = @("INSTALL_PRIVATE_IP","INSTALL_PUBLIC_IP","INSTALL_CAPTURED_AT",
                 "ASSIGNED_CITY","ASSIGNED_REGION","ASSIGNED_COUNTRY",
                 "ASSIGNED_LAT","ASSIGNED_LNG","ASSIGNED_SOURCE")

$kept = @()
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        $isManaged = $false
        foreach ($k in $managedKeys) { if ($line -match "^\s*$k\s*=") { $isManaged = $true; break } }
        if (-not $isManaged) { $kept += $line }
    }
}

$newLines = @(
    "# --- Location captured at install $(Get-Date -Format o) ---"
    "INSTALL_PRIVATE_IP=$privateIp"
    "INSTALL_PUBLIC_IP=$publicIp"
    "INSTALL_CAPTURED_AT=$(Get-Date -Format o)"
    "ASSIGNED_CITY=$aCity"
    "ASSIGNED_REGION=$aRegion"
    "ASSIGNED_COUNTRY=$aCountry"
    "ASSIGNED_LAT=$aLat"
    "ASSIGNED_LNG=$aLng"
    "ASSIGNED_SOURCE=$source"
)

if (-not (Test-Path $envFile)) { New-Item -ItemType File -Path $envFile -Force | Out-Null }
Set-Content -Path $envFile -Value ($kept + $newLines) -Encoding UTF8
Write-Host "       Wrote location to $envFile -> '$aCity' [$source]" -ForegroundColor Green
# LOCATION CAPTURE END

# ═══════════════════════════════════════════════════════════════════
# STEP 10: Configure HKLM\Run autostart (persists across restarts)
# ═══════════════════════════════════════════════════════════════════
Write-Host "[10/11] Configuring HKLM\Run autostart..." -ForegroundColor Yellow

Set-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run" `
    -Name ActivePulseAgent `
    -Value "`"$exe`" --watchdog" `
    -Type String

$verify = (Get-ItemProperty "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run" `
    -Name ActivePulseAgent).ActivePulseAgent
Write-Host "       Set: $verify" -ForegroundColor Green

# ═══════════════════════════════════════════════════════════════════
# STEP 11: LAUNCH AGENT AS THE DESKTOP USER (not as admin)
# ═══════════════════════════════════════════════════════════════════
Write-Host "[11/11] Launching agent as desktop user..." -ForegroundColor Yellow

# ─── Detect the logged-in console user ─────────────────────────────
$consoleUser = $null
try {
    $queryOutput = & query user 2>$null
    if ($queryOutput) {
        foreach ($line in ($queryOutput | Select-Object -Skip 1)) {
            $trimmed = $line.Trim()
            if ($trimmed.StartsWith(">")) {
                $consoleUser = ($trimmed -split '\s+')[0].TrimStart('>')
                break
            }
        }
        if (-not $consoleUser) {
            foreach ($line in ($queryOutput | Select-Object -Skip 1)) {
                if ($line -match '\bActive\b') {
                    $consoleUser = ($line.Trim() -split '\s+')[0].TrimStart('>')
                    break
                }
            }
        }
    }
} catch {
    Write-Host "       Could not query logged-in users: $($_.Exception.Message)" -ForegroundColor Yellow
}

if (-not $consoleUser) {
    Write-Host "       Could not detect logged-in user." -ForegroundColor Yellow
    Write-Host "       Agent will start automatically after next login (via HKLM\Run)." -ForegroundColor Gray
} else {
    Write-Host "       Detected logged-in user: $consoleUser" -ForegroundColor Green

    $adminUsersLower = $ADMIN_USERS | ForEach-Object { $_.ToLower() }
    $isSkipped = $adminUsersLower -contains $consoleUser.ToLower()

    if ($isSkipped) {
        Write-Host "       '$consoleUser' is on the admin skip list — agent would skip anyway." -ForegroundColor Gray
        Write-Host "       Skipping launch. Sign in as a regular user to test." -ForegroundColor Gray
    } else {
        Write-Host "       Creating one-time launch task for $consoleUser..."

        $taskName = "ActivePulse-LaunchOnce"
        schtasks /Delete /TN $taskName /F 2>$null | Out-Null

        # /RL LIMITED  → run with user's normal (non-admin) token
        # /IT          → interactive session (needed for hooks)
        # /SC ONCE     → one-time task
        # /F           → force overwrite if exists
        # schtasks /TR needs escaped quotes (backslash-quote) for paths with spaces
        $taskRun = '\"' + $exe + '\" --watchdog'
        $createArgs = @(
            "/Create", "/TN", $taskName,
            "/TR", $taskRun,
            "/SC", "ONCE",
            "/ST", (Get-Date).AddMinutes(1).ToString("HH:mm"),
            "/RU", $consoleUser,
            "/RL", "LIMITED",
            "/IT", "/F"
        )

        $created = & schtasks $createArgs 2>&1

        if ($LASTEXITCODE -eq 0) {
            Write-Host "       Task created. Running now..." -ForegroundColor Green
            & schtasks /Run /TN $taskName 2>&1 | Out-Null

            Write-Host "       Waiting 10 seconds for agent to start..."
            Start-Sleep -Seconds 10

            $procs = Get-WmiObject Win32_Process -Filter "Name='ActivePulse.exe'" -ErrorAction SilentlyContinue
            $runningForUser = @($procs | Where-Object {
                $o = $_.GetOwner()
                $o.User -ieq $consoleUser
            })

            if ($runningForUser.Count -gt 0) {
                Write-Host "       Agent is running as ${consoleUser}: $($runningForUser.Count) process(es)" -ForegroundColor Green
                $runningForUser | ForEach-Object {
                    Write-Host "         PID $($_.ProcessId) - $($_.Name)" -ForegroundColor Gray
                }
            } else {
                Write-Host "       WARNING: Agent did not start as $consoleUser after 10 seconds." -ForegroundColor Yellow
                Write-Host "       Ask $consoleUser to run in their PowerShell:" -ForegroundColor Yellow
                Write-Host '         Get-Process ActivePulse' -ForegroundColor Yellow
                Write-Host '         Get-Content "$env:TEMP\activepulse-skip.log" -Tail 5' -ForegroundColor Yellow
            }

            Start-Sleep -Seconds 2
            & schtasks /Delete /TN $taskName /F 2>&1 | Out-Null
            Write-Host "       Cleanup: launch task removed." -ForegroundColor Gray
        } else {
            Write-Host "       Task creation failed:" -ForegroundColor Red
            Write-Host "       $created" -ForegroundColor Red
            Write-Host "       Agent will still auto-start after next login (via HKLM\Run)." -ForegroundColor Gray
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# STEP 12: Summary
# ═══════════════════════════════════════════════════════════════════
Write-Host "[12/12] Done." -ForegroundColor Yellow

Write-Host ""
Write-Host "===============================================================" -ForegroundColor Cyan
Write-Host "  Installation Complete" -ForegroundColor Cyan
Write-Host "===============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Installed:    $exe"
Write-Host "  Autostart:    HKLM\Run\ActivePulseAgent = $verify"
Write-Host "  Install log:  $installLog"
Write-Host ""

if ($consoleUser -and -not $isSkipped) {
    Write-Host "  Agent should be running now as: $consoleUser" -ForegroundColor Green
    Write-Host "  Ask $consoleUser to verify by running (in their PowerShell):"
    Write-Host '    Get-Process ActivePulse'
    Write-Host '    Get-ChildItem "$env:LOCALAPPDATA\ActivePulse"'
    Write-Host '    Get-ChildItem "$env:LOCALAPPDATA\ActivePulse\logs"'
    Write-Host ""
    Write-Host "  Restart the machine to confirm autostart:"
    Write-Host "    Restart-Computer  # then sign back in as $consoleUser"
} else {
    Write-Host "  Agent will auto-start on next user login (via HKLM\Run)." -ForegroundColor Yellow
    Write-Host "  Sign in as a regular user (e.g. gaytri.sonar) to test."
}
Write-Host ""