# ============================================================
#  ActivePulse - Full Uninstall / Teardown
#
#  Cleanly removes ActivePulse so it does NOT come back:
#    1. Stop + delete the watchdog task and its wscript process FIRST
#       (otherwise it relaunches the agent within ~10s of any kill).
#    2. Delete any leftover launch task.
#    3. Kill every ActivePulse.exe (all users, incl. adcadmin).
#    4. Remove HKLM\Run and every loaded HKU\<SID>\Run autostart entry.
#    5. Delete the guarded launcher / watchdog VBS, locks, and user data.
#    6. Run the Inno uninstaller (unins000.exe) to remove program files.
#    7. Verify nothing is left running.
#
#  Run in an ELEVATED PowerShell.
# ============================================================

$ErrorActionPreference = "Continue"

$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Run this script as Administrator." -ForegroundColor Red
    exit 1
}

$candidateDirs = @(
    "C:\Program Files\ActivePulse",
    "C:\Program Files (x86)\ActivePulse"
)
$INSTALL_DIR = $candidateDirs | Where-Object { Test-Path $_ } | Select-Object -First 1

Write-Host "=========================================="
Write-Host " ActivePulse Uninstall / Teardown"
Write-Host "=========================================="

# ═══════════════════════════════════════════════════════════════════
# STEP 1: Stop the watchdog FIRST (task + wscript process)
# ═══════════════════════════════════════════════════════════════════
Write-Host "`n[1/7] Stopping self-heal watchdog..." -ForegroundColor Yellow
schtasks.exe /End    /TN "ActivePulseWatchdog"    2>$null | Out-Null
schtasks.exe /Delete /TN "ActivePulseWatchdog" /F 2>$null | Out-Null
Get-WmiObject Win32_Process -Filter "Name='wscript.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -and ($_.CommandLine -like "*watchdog.vbs*" -or $_.CommandLine -like "*launch-agent.vbs*")) {
        Write-Host "       Killing watchdog wscript PID $($_.ProcessId)" -ForegroundColor Gray
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
}
Write-Host "       Watchdog stopped." -ForegroundColor Green

# ═══════════════════════════════════════════════════════════════════
# STEP 2: Delete any other ActivePulse scheduled tasks
# ═══════════════════════════════════════════════════════════════════
Write-Host "[2/7] Removing ActivePulse scheduled tasks..." -ForegroundColor Yellow
$tasks = Get-ScheduledTask -TaskName "ActivePulse*" -ErrorAction SilentlyContinue
if ($tasks) {
    foreach ($t in $tasks) {
        $full = ($t.TaskPath.TrimEnd('\') + '\' + $t.TaskName).TrimStart('\')
        schtasks.exe /Delete /TN "$full" /F 2>$null | Out-Null
        Write-Host "       Removed task: $full" -ForegroundColor Green
    }
}
foreach ($name in @("ActivePulseLaunch", "ActivePulseWatchdog", "ActivePulseAgent")) {
    schtasks.exe /Delete /TN $name /F 2>$null | Out-Null
}

# ═══════════════════════════════════════════════════════════════════
# STEP 3: Kill every ActivePulse.exe (retry to be sure it stays dead)
# ═══════════════════════════════════════════════════════════════════
Write-Host "[3/7] Killing all ActivePulse processes..." -ForegroundColor Yellow
for ($pass = 1; $pass -le 3; $pass++) {
    $procs = Get-WmiObject Win32_Process -Filter "Name='ActivePulse.exe'" -ErrorAction SilentlyContinue
    if (-not $procs) { break }
    foreach ($p in $procs) {
        $owner = $p.GetOwner()
        Write-Host "       Killing PID $($p.ProcessId) ($($owner.Domain)\$($owner.User))" -ForegroundColor Gray
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
}
# Also kill any java(w) launched from an ActivePulse path.
Get-WmiObject Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -and $_.CommandLine -like "*ctivePulse*") {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
}
Write-Host "       Processes terminated." -ForegroundColor Green

# ═══════════════════════════════════════════════════════════════════
# STEP 4: Remove HKLM\Run autostart entry
# ═══════════════════════════════════════════════════════════════════
Write-Host "[4/7] Removing HKLM\Run entry..." -ForegroundColor Yellow
$runKeyPath = "Software\Microsoft\Windows\CurrentVersion\Run"
$hklmRun = "HKLM:\$runKeyPath"
if (Get-ItemProperty -Path $hklmRun -Name "ActivePulseAgent" -ErrorAction SilentlyContinue) {
    Remove-ItemProperty -Path $hklmRun -Name "ActivePulseAgent" -Force -ErrorAction SilentlyContinue
    Write-Host "       Removed HKLM\Run\ActivePulseAgent" -ForegroundColor Green
} else {
    Write-Host "       No HKLM\Run entry found." -ForegroundColor Green
}

# ═══════════════════════════════════════════════════════════════════
# STEP 5: Remove HKU\<SID>\Run entry from every loaded user hive
# ═══════════════════════════════════════════════════════════════════
Write-Host "[5/7] Removing per-user HKU\Run entries..." -ForegroundColor Yellow
$hkuRemoved = 0
Get-ChildItem "Registry::HKEY_USERS" -ErrorAction SilentlyContinue | ForEach-Object {
    $sid = Split-Path $_.Name -Leaf
    if ($sid -notlike "S-1-5-21-*") { return }   # skip system/service hives
    $hkuRun = "Registry::HKEY_USERS\$sid\$runKeyPath"
    if (Get-ItemProperty -Path $hkuRun -Name "ActivePulseAgent" -ErrorAction SilentlyContinue) {
        Remove-ItemProperty -Path $hkuRun -Name "ActivePulseAgent" -Force -ErrorAction SilentlyContinue
        Write-Host "       Removed HKU\$sid\Run\ActivePulseAgent" -ForegroundColor Green
        $hkuRemoved++
    }
}
if ($hkuRemoved -eq 0) { Write-Host "       No loaded per-user entries found." -ForegroundColor Green }
Write-Host "       (Note: entries in the hive of a user who is NOT logged on" -ForegroundColor DarkGray
Write-Host "        are removed the next time this runs while they are logged in.)" -ForegroundColor DarkGray

# ═══════════════════════════════════════════════════════════════════
# STEP 6: Delete VBS launchers, locks, and user data
# ═══════════════════════════════════════════════════════════════════
Write-Host "[6/7] Deleting launcher scripts, locks, and user data..." -ForegroundColor Yellow
if ($INSTALL_DIR) {
    foreach ($f in @("watchdog.vbs", "launch-agent.vbs")) {
        $fp = Join-Path $INSTALL_DIR $f
        if (Test-Path $fp) { Remove-Item $fp -Force -ErrorAction SilentlyContinue; Write-Host "       Removed: $fp" -ForegroundColor Gray }
    }
}
Get-ChildItem "C:\Users" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    foreach ($sub in @("AppData\Local\ActivePulse", ".activepulse")) {
        $full = Join-Path $_.FullName $sub
        if (Test-Path $full) {
            Remove-Item $full -Recurse -Force -ErrorAction SilentlyContinue
            Write-Host "       Removed: $full" -ForegroundColor Gray
        }
    }
}
Remove-Item "$env:TEMP\activepulse-*.log" -Force -ErrorAction SilentlyContinue
Remove-Item "C:\Temp\ActivePulse*.xml"     -Force -ErrorAction SilentlyContinue

# ═══════════════════════════════════════════════════════════════════
# STEP 7: Run the Inno uninstaller, then verify
# ═══════════════════════════════════════════════════════════════════
Write-Host "[7/7] Running Inno uninstaller..." -ForegroundColor Yellow
$uninstaller = if ($INSTALL_DIR) { Join-Path $INSTALL_DIR "unins000.exe" } else { $null }
if ($uninstaller -and (Test-Path $uninstaller)) {
    Start-Process -FilePath $uninstaller -ArgumentList "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART" -Wait
    Start-Sleep -Seconds 4
    Write-Host "       Uninstaller finished." -ForegroundColor Green
} else {
    Write-Host "       No unins000.exe found (already removed or different installer)." -ForegroundColor DarkYellow
    if ($INSTALL_DIR -and (Test-Path $INSTALL_DIR)) {
        Remove-Item $INSTALL_DIR -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "       Removed install dir: $INSTALL_DIR" -ForegroundColor Gray
    }
}

Start-Sleep -Seconds 2
$still = Get-WmiObject Win32_Process -Filter "Name='ActivePulse.exe'" -ErrorAction SilentlyContinue
if ($still) {
    Write-Host "`n       WARNING: ActivePulse.exe is STILL running:" -ForegroundColor Red
    $still | ForEach-Object { Write-Host ("         PID {0} ({1})" -f $_.ProcessId, $_.GetOwner().User) -ForegroundColor Red }
    Write-Host "       Re-run this script; a watchdog may have restarted it before removal." -ForegroundColor Red
} else {
    Write-Host "`n       Verified: no ActivePulse.exe running." -ForegroundColor Green
}

Write-Host "`n=========================================="
Write-Host " Uninstall complete."
Write-Host "=========================================="
