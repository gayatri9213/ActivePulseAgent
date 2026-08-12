# ============================================================
#  ActivePulse Deployment Script  (Inno Setup edition)
#  Adapted from the MSI 11-step script:
#   - STEP 2 uninstall  -> Inno unins000.exe
#   - STEP 5 find        -> Inno installer .exe
#   - STEP 7 install     -> Inno /VERYSILENT
#  Everything else (kill, clean, locks, location, autostart,
#  launch-as-user) is unchanged from the working MSI script.
#
#  Run in an ELEVATED PowerShell.
# ============================================================

$ErrorActionPreference = "Continue"

# --- Config ---------------------------------------------------
# The Inno installer file name. If your OutputBaseFilename is
# "ActivePulse", set this to "ActivePulse.exe". If you renamed it
# to "ActivePulseSetup.exe" (recommended, avoids clashing with the
# agent exe), set that here.
$INSTALLER_NAMES = @("ActivePulseSetup.exe", "ActivePulse-Setup.exe", "ActivePulse.exe")

$ADMIN_USERS = @("adcadmin","administrator","Administrator","SYSTEM","DefaultAccount","WDAGUtilityAccount","admin","user")

# Detect the real install dir (Inno may use Program Files or (x86)).
$candidateDirs = @(
    "C:\Program Files\ActivePulse",
    "C:\Program Files (x86)\ActivePulse"
)
$INSTALL_DIR = $candidateDirs | Where-Object { Test-Path (Join-Path $_ "ActivePulse.exe") } | Select-Object -First 1
if (-not $INSTALL_DIR) { $INSTALL_DIR = "C:\Program Files\ActivePulse" }   # default for fresh install

# --- Verify admin ---------------------------------------------
$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Run this script as Administrator." -ForegroundColor Red
    exit 1
}

Write-Host "=========================================="
Write-Host " ActivePulse Deployment (Inno Setup)"
Write-Host "=========================================="

# ═══════════════════════════════════════════════════════════════════
# STEP 0: Detect logged-in (interactive) user
# ═══════════════════════════════════════════════════════════════════
Write-Host "`n[0/11] Detecting logged-in user..." -ForegroundColor Yellow
$consoleUser = $null
$query = query user 2>$null
foreach ($line in ($query | Select-Object -Skip 1)) {
    if ($line -match "Active") {
        $consoleUser = ($line.Trim() -split '\s+')[0].TrimStart(">")
        break
    }
}
if (-not $consoleUser) {
    Write-Host "       No interactive user detected. Will install only." -ForegroundColor Yellow
} else {
    Write-Host "       Logged-in user: $consoleUser" -ForegroundColor Green
}

# ═══════════════════════════════════════════════════════════════════
# STEP 1: Kill ALL ActivePulse processes (all users)
# ═══════════════════════════════════════════════════════════════════
Write-Host "[1/11] Killing all ActivePulse processes..." -ForegroundColor Yellow

# Stop the self-heal watchdog FIRST, otherwise it would relaunch the agent
# within ~10s while we are trying to kill/uninstall it below.
schtasks.exe /End    /TN "ActivePulseWatchdog"    2>$null | Out-Null
schtasks.exe /Delete /TN "ActivePulseWatchdog" /F 2>$null | Out-Null
Get-WmiObject Win32_Process -Filter "Name='wscript.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -and $_.CommandLine -like "*watchdog.vbs*") {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

# Kill EVERY ActivePulse.exe regardless of owner. This includes the unwanted
# admin-owned copy (e.g. adcadmin) that an old HKLM\Run entry started.
$killed = 0
Get-WmiObject Win32_Process -Filter "Name='ActivePulse.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    $owner = $_.GetOwner()
    Write-Host "       Killing ActivePulse PID $($_.ProcessId)  ($($owner.Domain)\$($consoleUser))"
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    $killed++
}
Get-WmiObject Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -and ($_.CommandLine -like "*activepulse*" -or $_.CommandLine -like "*ActivePulse*")) {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        $killed++
    }
}
Start-Sleep -Seconds 3
Write-Host "       Killed $killed process(es)" -ForegroundColor Green

# ═══════════════════════════════════════════════════════════════════
# STEP 2: Uninstall previous version (Inno unins000.exe)
# ═══════════════════════════════════════════════════════════════════
Write-Host "[2/11] Uninstalling previous version (Inno)..." -ForegroundColor Yellow
$uninstaller = Join-Path $INSTALL_DIR "unins000.exe"
if (Test-Path $uninstaller) {
    Write-Host "       Found: $uninstaller"
    Start-Process -FilePath $uninstaller `
        -ArgumentList "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART" -Wait
    Start-Sleep -Seconds 5
    Write-Host "       Uninstalled" -ForegroundColor Green
} else {
    Write-Host "       No previous Inno installation found" -ForegroundColor Green
}

# ═══════════════════════════════════════════════════════════════════
# STEP 3: Clean admin user data
# ═══════════════════════════════════════════════════════════════════
Write-Host "[3/11] Cleaning ActivePulse data..." -ForegroundColor Yellow
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
# STEP 4: Clean lock files (all users)
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
# STEP 5: Find the Inno installer (.exe)
# ═══════════════════════════════════════════════════════════════════
Write-Host "[5/11] Locating ActivePulse Inno installer..." -ForegroundColor Yellow
$installer = $null

# Build search list: script dir, dist/output subfolders, all users' Downloads, C:\Temp, C:\Installers
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$searchDirs = @(
    $scriptDir,
    (Join-Path $scriptDir "output"),
    (Join-Path $scriptDir "dist"),
    "C:\Temp",
    "C:\Installers"
)
Get-ChildItem "C:\Users" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $searchDirs += "$($_.FullName)\Downloads"
}

$found = @()
foreach ($d in $searchDirs) {
    if ($d -and (Test-Path $d)) {
        foreach ($name in $INSTALLER_NAMES) {
            $found += Get-ChildItem $d -Filter $name -ErrorAction SilentlyContinue |
                      Where-Object { $_.FullName -notlike "$INSTALL_DIR*" }   # exclude the installed agent exe
        }
    }
}
$found = $found | Sort-Object LastWriteTime -Descending
if ($found) {
    $installer = $found[0].FullName
    Write-Host "       Found: $installer" -ForegroundColor Green
    Write-Host "       Size:  $([math]::Round($found[0].Length / 1MB, 1)) MB"
    Write-Host "       Date:  $($found[0].LastWriteTime)"
}

if (-not $installer) {
    Write-Host "       ERROR: Inno installer not found." -ForegroundColor Red
    Write-Host "       Looked for: $($INSTALLER_NAMES -join ', ')" -ForegroundColor Red
    Write-Host "       Place it next to this script, or in C:\Temp." -ForegroundColor Red
    return
}

# ═══════════════════════════════════════════════════════════════════
# STEP 6: Prepare installer in place (NO staging to C:\Temp)
# ═══════════════════════════════════════════════════════════════════
Write-Host "[6/11] Preparing installer (running in place)..." -ForegroundColor Yellow

# Zone.Identifier (Mark-of-the-Web) hatao, warna "Unknown Publisher"
# dialog aayega aur /VERYSILENT click ka wait karta reh jayega.
Unblock-File -Path $installer -ErrorAction SilentlyContinue
Remove-Item -Path $installer -Stream Zone.Identifier -ErrorAction SilentlyContinue

$zone = Get-Item -Path $installer -Stream * -ErrorAction SilentlyContinue |
        Where-Object { $_.Stream -eq "Zone.Identifier" }
if ($zone) {
    Write-Host "       WARNING: Zone.Identifier hata nahi (read-only path?) - dialog aa sakta hai." -ForegroundColor Yellow
} else {
    Write-Host "       Unblocked (no Zone.Identifier)" -ForegroundColor Green
}
Write-Host "       Using: $installer" -ForegroundColor Green

# ═══════════════════════════════════════════════════════════════════
# STEP 7: Install silently (Inno) - poll till done, kill only on timeout
# ═══════════════════════════════════════════════════════════════════
Write-Host "[7/11] Installing silently (Inno /VERYSILENT)..." -ForegroundColor Yellow
$innoArgs   = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART"
$maxWaitSec = 120   # slow HDD / AV scan wali machine ke liye headroom

$env:SEE_MASK_NOZONECHECKS = 1
$proc = Start-Process -FilePath $installer -ArgumentList $innoArgs -PassThru
Remove-Item Env:\SEE_MASK_NOZONECHECKS -ErrorAction SilentlyContinue

# Installer ka apna process tree (parent + Inno ka .tmp child).
# Installed agent ko galti se pakadne se bachne ke liye path check karte hain.
$baseName = [IO.Path]::GetFileNameWithoutExtension($installer)
function Get-SetupProcs {
    Get-Process -ErrorAction SilentlyContinue |
        Where-Object {
            ($_.ProcessName -like "$baseName*") -and
            ($_.Path -notlike "C:\Program Files\ActivePulse\*") -and
            ($_.Path -notlike "C:\Program Files (x86)\ActivePulse\*")
        }
}

$waited = 0
while ($waited -lt $maxWaitSec) {
    Start-Sleep -Seconds 2
    $waited += 2
    if (-not (Get-SetupProcs)) {
        Write-Host "       Installer finished after ${waited}s" -ForegroundColor Green
        break
    }
    if ($waited % 20 -eq 0) { Write-Host "       ...still installing (${waited}s)" -ForegroundColor Gray }
}

if (Get-SetupProcs) {
    Write-Host "       Timed out after ${maxWaitSec}s - closing installer." -ForegroundColor Yellow
    Get-SetupProcs | ForEach-Object {
        Write-Host "       Closing $($_.ProcessName) (PID $($_.Id))" -ForegroundColor Gray
        Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
} elseif ($proc.HasExited -and $proc.ExitCode -ne 0) {
    Write-Host "       Installation reported failure (exit $($proc.ExitCode))." -ForegroundColor Red
    return
}
Start-Sleep -Seconds 3

# Re-detect install dir now that it's freshly installed
$INSTALL_DIR = $candidateDirs | Where-Object { Test-Path (Join-Path $_ "ActivePulse.exe") } | Select-Object -First 1
if (-not $INSTALL_DIR) { $INSTALL_DIR = "C:\Program Files\ActivePulse" }
# ═══════════════════════════════════════════════════════════════════
# STEP 8: Verify install
# ═══════════════════════════════════════════════════════════════════
Write-Host "[8/11] Verifying install..." -ForegroundColor Yellow
# Poll up to ~12s for the exe (STEP 7 no longer blocks, install may still be writing).
$exe = "$INSTALL_DIR\ActivePulse.exe"
$tries = 0
while (-not (Test-Path $exe) -and $tries -lt 6) {
    Start-Sleep -Seconds 2
    $INSTALL_DIR = $candidateDirs | Where-Object { Test-Path (Join-Path $_ "ActivePulse.exe") } | Select-Object -First 1
    if (-not $INSTALL_DIR) { $INSTALL_DIR = "C:\Program Files\ActivePulse" }
    $exe = "$INSTALL_DIR\ActivePulse.exe"
    $tries++
}
if (Test-Path $exe) {
    Write-Host "       OK: $exe" -ForegroundColor Green
} else {
    Write-Host "       ERROR: ActivePulse.exe not found in either Program Files location!" -ForegroundColor Red
    return
}



# ═══════════════════════════════════════════════════════════════════
# Helper: resolve a logged-on user's SID WITHOUT contacting the domain
# controller. Matches the loaded profile folder in ProfileList, so it works
# even when the machine's domain trust relationship is broken (cached logon).
# ═══════════════════════════════════════════════════════════════════
function Get-ConsoleUserSid {
    param([string]$UserName)
    $leaf = ($UserName -split '\\')[-1]   # strip any DOMAIN\ prefix
    $base = "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList"
    foreach ($k in (Get-ChildItem $base -ErrorAction SilentlyContinue)) {
        $sidName = Split-Path $k.Name -Leaf
        if ($sidName -notlike "S-1-5-21-*") { continue }   # real interactive accounts only
        $pip = (Get-ItemProperty $k.PSPath -Name ProfileImagePath -ErrorAction SilentlyContinue).ProfileImagePath
        if (-not $pip) { continue }
        $profileLeaf = Split-Path $pip -Leaf
        if ($profileLeaf -ieq $leaf -or $profileLeaf -ilike "$leaf.*") {
            return $sidName
        }
    }
    return $null
}

# ═══════════════════════════════════════════════════════════════════
# STEP 9: Autostart (HKLM entry KEPT, but guarded + per-user HKU direct)
#
# HKLM\Run fires for EVERY user, so pointing it straight at the exe starts a
# second agent under admin/service accounts (e.g. adcadmin). To KEEP the HKLM
# entry but avoid that, HKLM\Run points at a small guarded launcher (VBS) that:
#   - exits immediately for excluded admin/service users ($ADMIN_USERS), and
#   - skips launching if an agent is already running.
# The console user ALSO gets a direct HKU\<SID>\Run entry, so their agent
# starts straight from their own hive. Result: HKLM entry present (as wanted),
# but a running agent only ever appears for the intended user, never adcadmin.
# ═══════════════════════════════════════════════════════════════════
Write-Host "[9/11] Configuring autostart (HKLM guarded + per-user)..." -ForegroundColor Yellow
$runKeyPath = "Software\Microsoft\Windows\CurrentVersion\Run"
$exeQuoted  = "`"$exe`""
$launchVbs  = Join-Path $INSTALL_DIR "launch-agent.vbs"

# --- Build the guarded launcher used by HKLM\Run ---
# Exclusion list is derived from $ADMIN_USERS (lower-cased) so it stays in sync.
$exclList = ($ADMIN_USERS | ForEach-Object { '"' + ($_.ToLower()) + '"' }) -join ","
$launcher = @"
' ActivePulse guarded launcher (used by HKLM\Run). Runs for every user at
' logon but EXITS for excluded admin/service accounts, so the agent never
' starts for e.g. adcadmin. Normal users get the agent, launched hidden.
Option Explicit
Dim sh, net, wmi, procs, user, exePath, excluded, i
exePath  = "$exe"
excluded = Array($exclList)
Set net  = CreateObject("WScript.Network")
user = LCase(net.UserName)
For i = 0 To UBound(excluded)
  If user = excluded(i) Then WScript.Quit 0
Next
' Skip if an agent is already running (avoids a duplicate vs the HKU entry).
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
Set procs = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE Name='ActivePulse.exe'")
If procs.Count > 0 Then WScript.Quit 0
Set sh = CreateObject("WScript.Shell")
sh.Run """" & exePath & """", 0, False
"@
Set-Content -Path $launchVbs -Value $launcher -Encoding ASCII
Write-Host "       Wrote guarded launcher: $launchVbs" -ForegroundColor Green

# --- HKLM\Run -> guarded launcher (entry KEPT, but admin-safe) ---
$hklmRun = "HKLM:\$runKeyPath"
$hklmVal = "wscript.exe `"$launchVbs`""
Set-ItemProperty -Path $hklmRun -Name "ActivePulseAgent" -Value $hklmVal -Force
Write-Host "       Set HKLM Run key: $hklmVal" -ForegroundColor Green

# --- HKU\<SID>\Run -> direct exe for the console user ---
if ($consoleUser) {
    $sid = Get-ConsoleUserSid $consoleUser
    if ($sid -and (Test-Path "Registry::HKEY_USERS\$sid")) {
        try {
            $hkuRun = "Registry::HKEY_USERS\$sid\$runKeyPath"
            if (-not (Test-Path $hkuRun)) { New-Item -Path $hkuRun -Force | Out-Null }
            Set-ItemProperty -Path $hkuRun -Name "ActivePulseAgent" -Value $exeQuoted -Force
            Write-Host "       Set per-user Run key for $consoleUser (HKU\$sid)" -ForegroundColor Green
        } catch {
            Write-Host "       Per-user Run key failed: $($_.Exception.Message)" -ForegroundColor Red
        }
    } else {
        Write-Host "       Could not resolve loaded hive for $consoleUser; HKLM guarded launcher covers it." -ForegroundColor Yellow
    }
} else {
    Write-Host "       No interactive user; HKLM guarded launcher will handle logon." -ForegroundColor Yellow
}

# ═══════════════════════════════════════════════════════════════════
# STEP 10: Launch agent in the desktop user's session (SID-based, DC-free)
#
# Registers a one-time task by XML using the console user's SID with
# LogonType=InteractiveToken. This needs no password and does NOT contact the
# domain controller, so it works even when the machine's domain trust
# relationship is broken (why we no longer pass DOMAIN\user to schtasks /RU).
#
# This task has NO trigger and is DELETED at the end of this step, so it does
# not persist and does not compete with the Run keys at future logons.
# ═══════════════════════════════════════════════════════════════════
if ($consoleUser) {
    Write-Host "[10/11] Launching agent as $consoleUser..." -ForegroundColor Yellow
    $taskName = "ActivePulseLaunch"
    $sid11 = Get-ConsoleUserSid $consoleUser

    if (-not $sid11) {
        Write-Host "       Could not resolve SID for $consoleUser; skipping immediate launch." -ForegroundColor Yellow
        Write-Host "       Agent will start on next login via the Run key." -ForegroundColor Yellow
    } else {
        # remove any leftover task first
        schtasks.exe /Delete /TN $taskName /F 2>$null | Out-Null

        # InteractiveToken task, addressed by SID (no password, no DC contact).
        $taskXml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>ActivePulse one-time interactive launch</Description>
  </RegistrationInfo>
  <Principals>
    <Principal id="Author">
      <UserId>$sid11</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>7</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>$exe</Command>
    </Exec>
  </Actions>
</Task>
"@
        $xmlPath = "C:\Temp\ActivePulseLaunch.xml"
        New-Item -ItemType Directory -Force -Path "C:\Temp" | Out-Null
        # Task Scheduler XML must be Unicode (UTF-16).
        Set-Content -Path $xmlPath -Value $taskXml -Encoding Unicode

        schtasks.exe /Create /TN $taskName /XML $xmlPath /F | Out-Null
        schtasks.exe /Run /TN $taskName | Out-Null
        Remove-Item $xmlPath -Force -ErrorAction SilentlyContinue

        Write-Host "       Waiting for agent to start..."
        Start-Sleep -Seconds 12

        $bareUser = ($consoleUser -split '\\')[-1]
        $running = Get-WmiObject Win32_Process -Filter "Name='ActivePulse.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.GetOwner().User -ieq $bareUser }

        if ($running) {
            Write-Host "       ActivePulse running as $consoleUser " -ForegroundColor Green
        } else {
            Write-Host "       Agent not detected as $consoleUser yet." -ForegroundColor Yellow
            Write-Host "       It will also start on next login via the Run key." -ForegroundColor Yellow
            Write-Host "       Check log: C:\Users\$consoleUser\AppData\Local\ActivePulse\logs\" -ForegroundColor Yellow
        }

        # Delete the one-time task so it never persists as a logon trigger.
        schtasks.exe /Delete /TN $taskName /F 2>$null | Out-Null
    }
} else {
    Write-Host "[10/11] No interactive user - agent will start on next login via Run key." -ForegroundColor Yellow
}

# ═══════════════════════════════════════════════════════════════════
# STEP 12: Install self-heal watchdog (relaunch within ~10s if killed)
#
# Writes a tiny hidden VBS watchdog next to the agent and registers a
# LOGON-triggered task (InteractiveToken -> user session) that runs it. The
# watchdog polls every 10s and relaunches ActivePulse.exe ONLY if it is not
# already running - so it NEVER creates a duplicate and NEVER produces the
# 0xFFFFFFFF "duplicate rejected" noise a blind launch task would.
#
# Recovery times:
#   - Agent killed from Task Manager  -> back in <= ~10s (watchdog poll).
#   - Watchdog itself killed          -> back in <= ~1 min (task restart).
# Run keys are left intact and still give instant start at logon; the watchdog
# only adds keep-alive on top.
# ═══════════════════════════════════════════════════════════════════
Write-Host "[11/11] Installing self-heal watchdog..." -ForegroundColor Yellow
$wdVbs  = Join-Path $INSTALL_DIR "watchdog.vbs"
$wdTask = "ActivePulseWatchdog"

# --- write the hidden VBS watchdog (wscript = no window) ---
$vbs = @"
' ActivePulse self-heal watchdog. Relaunches the agent within ~10s if it is
' killed (e.g. from Task Manager). Runs hidden via wscript (no window).
Option Explicit
Dim sh, wmi, procs, exePath
exePath = "$exe"
Set sh  = CreateObject("WScript.Shell")
Set wmi = GetObject("winmgmts:\\.\root\cimv2")
Do
  Set procs = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE Name='ActivePulse.exe'")
  If procs.Count = 0 Then
    sh.Run """" & exePath & """", 0, False
  End If
  WScript.Sleep 10000
Loop
"@
Set-Content -Path $wdVbs -Value $vbs -Encoding ASCII
Write-Host "       Wrote watchdog: $wdVbs" -ForegroundColor Green

# --- register the logon watchdog task (needs the console user's SID) ---
$wdSid = if ($consoleUser) { Get-ConsoleUserSid $consoleUser } else { $null }
if ($wdSid) {
    schtasks.exe /Delete /TN $wdTask /F 2>$null | Out-Null
    $wdXml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>ActivePulse self-heal watchdog (relaunch agent if killed)</Description>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <UserId>$wdSid</UserId>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>$wdSid</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>7</Priority>
    <RestartOnFailure>
      <Interval>PT1M</Interval>
      <Count>999</Count>
    </RestartOnFailure>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>wscript.exe</Command>
      <Arguments>"$wdVbs"</Arguments>
    </Exec>
  </Actions>
</Task>
"@
    $wdXmlPath = "C:\Temp\ActivePulseWatchdog.xml"
    New-Item -ItemType Directory -Force -Path "C:\Temp" | Out-Null
    # Task Scheduler XML must be Unicode (UTF-16).
    Set-Content -Path $wdXmlPath -Value $wdXml -Encoding Unicode
    schtasks.exe /Create /TN $wdTask /XML $wdXmlPath /F | Out-Null
    schtasks.exe /Run /TN $wdTask | Out-Null
    Remove-Item $wdXmlPath -Force -ErrorAction SilentlyContinue
    Write-Host "       Watchdog task registered and started (10s poll)." -ForegroundColor Green
} else {
    Write-Host "       No console user SID; watchdog task skipped (Run key still autostarts)." -ForegroundColor Yellow
}

Write-Host "`n=========================================="
Write-Host " Deployment completed."
Write-Host " Install dir: $INSTALL_DIR"
Write-Host "=========================================="