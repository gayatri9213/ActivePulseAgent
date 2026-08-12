#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# ActivePulse — macOS Installation Script (v1.0.0)
#
# THIS SCRIPT IS macOS-ONLY. It does not touch Windows or Linux in any
# way and has no effect on the Windows installer/PowerShell script or
# the Linux install script.
#
# USAGE:
#   1. Copy the ActivePulse .dmg next to this script (or into ~/Downloads,
#      /tmp, or a "dist" folder next to this script).
#   2. sudo bash install-activepulse-agent-v1_0_0-updated-macos.sh
#
# What it does (mirrors the Windows installer's approach):
#   1.  Kills any running ActivePulse process
#   2.  Removes any previous /Applications/ActivePulse.app
#   3.  Removes stale lock files (all users)
#   4.  Locates the .dmg, mounts it, and copies ActivePulse.app to
#       /Applications
#   5.  Clears the Gatekeeper quarantine flag (com.apple.quarantine) on
#       the unsigned .app — otherwise macOS silently blocks RunAtLoad
#       and the LaunchAgent never actually starts the process
#   6.  Writes a MACHINE-WIDE LaunchAgent at
#         /Library/LaunchAgents/com.aress.activepulse.plist
#      This is the macOS equivalent of Windows' HKLM\Run: every user
#      who logs into the GUI gets ActivePulse started for them
#      automatically, without the app needing to run once first.
#   7.  Bootstraps (loads) the LaunchAgent NOW into the current console
#       user's GUI domain, which also launches the agent immediately
#       (RunAtLoad) — no reboot/logout needed to verify the install.
#   8.  Verifies logs actually got created and prints a summary.
#
# ROOT CAUSE THIS FIXES:
#   The jpackage .dmg only contains the .app bundle for drag-to-Applications
#   install — nothing ever copies it, nothing ever launches it, and nothing
#   ever installs a LaunchAgent. Additionally, unsigned apps downloaded to
#   disk get an com.apple.quarantine xattr that blocks LaunchAgent-triggered
#   launches (RunAtLoad silently does nothing). This script is the missing
#   piece.
# ═══════════════════════════════════════════════════════════════════
set -u

APP_NAME="ActivePulse.app"
APP_PATH="/Applications/$APP_NAME"
LAUNCHER="$APP_PATH/Contents/MacOS/ActivePulse"
LABEL="com.aress.activepulse"
LAUNCH_AGENT_DIR="/Library/LaunchAgents"
LAUNCH_AGENT_FILE="$LAUNCH_AGENT_DIR/$LABEL.plist"

# ─── Must run as root ──────────────────────────────────────────────
if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: Must run as root (sudo)." >&2
    exit 1
fi

echo ""
echo "==============================================================="
echo "  ActivePulse v1.0.0 - macOS Install"
echo "==============================================================="
echo ""

# ═══════════════════════════════════════════════════════════════════
# STEP 1: Kill any running ActivePulse process
# ═══════════════════════════════════════════════════════════════════
echo "[1/8] Killing any running ActivePulse process..."
pkill -f "$LAUNCHER" 2>/dev/null || true
pkill -f "MacOS/ActivePulse" 2>/dev/null || true
sleep 2
echo "      Done"

# ═══════════════════════════════════════════════════════════════════
# STEP 2: Remove any previous install
# ═══════════════════════════════════════════════════════════════════
echo "[2/8] Removing previous install (if any)..."
if [ -d "$APP_PATH" ]; then
    rm -rf "$APP_PATH"
    echo "      Removed: $APP_PATH"
else
    echo "      Nothing to remove"
fi

# ═══════════════════════════════════════════════════════════════════
# STEP 3: Clean stale lock files (all users)
# ═══════════════════════════════════════════════════════════════════
echo "[3/8] Cleaning stale lock files (all users)..."
lock_count=0
for home_dir in /Users/*; do
    [ -d "$home_dir" ] || continue
    for data_dir in "$home_dir/Library/Application Support/ActivePulse" "$home_dir/Library/Application Support/ActivePulse-Test"; do
        for lock_name in activepulse.lock watchdog.lock; do
            lock_path="$data_dir/$lock_name"
            if [ -f "$lock_path" ]; then
                rm -f "$lock_path" && lock_count=$((lock_count + 1))
            fi
        done
    done
done
echo "      Cleaned $lock_count lock file(s)"

# ═══════════════════════════════════════════════════════════════════
# STEP 4: Locate + mount the .dmg, copy the .app
# ═══════════════════════════════════════════════════════════════════
echo "[4/8] Locating ActivePulse .dmg..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DMG=""
search_dirs=("$SCRIPT_DIR" "$SCRIPT_DIR/dist" "/tmp")
for home_dir in /Users/*; do
    [ -d "$home_dir/Downloads" ] && search_dirs+=("$home_dir/Downloads")
done

newest_time=0
for dir in "${search_dirs[@]}"; do
    [ -d "$dir" ] || continue
    while IFS= read -r -d '' f; do
        mtime=$(stat -f %m "$f" 2>/dev/null || echo 0)
        if [ "$mtime" -gt "$newest_time" ]; then
            newest_time=$mtime
            DMG="$f"
        fi
    done < <(find "$dir" -maxdepth 2 -iname "activepulse*.dmg" -print0 2>/dev/null)
done

if [ -z "$DMG" ]; then
    echo "      ERROR: No .dmg found. Place ActivePulse*.dmg next to this script, in /tmp, or in a Downloads folder." >&2
    exit 1
fi
echo "      Found: $DMG"

echo "      Mounting..."
MOUNT_POINT="/tmp/activepulse-mount-$$"
mkdir -p "$MOUNT_POINT"
hdiutil attach "$DMG" -mountpoint "$MOUNT_POINT" -nobrowse -quiet
if [ $? -ne 0 ]; then
    echo "      ERROR: hdiutil attach failed." >&2
    rmdir "$MOUNT_POINT" 2>/dev/null
    exit 1
fi

SRC_APP=$(find "$MOUNT_POINT" -maxdepth 1 -iname "*.app" | head -n 1)
if [ -z "$SRC_APP" ]; then
    echo "      ERROR: No .app found inside $DMG" >&2
    hdiutil detach "$MOUNT_POINT" -quiet 2>/dev/null
    rmdir "$MOUNT_POINT" 2>/dev/null
    exit 1
fi

cp -R "$SRC_APP" "/Applications/"
hdiutil detach "$MOUNT_POINT" -quiet
rmdir "$MOUNT_POINT" 2>/dev/null
echo "      Copied to: $APP_PATH"

# ═══════════════════════════════════════════════════════════════════
# STEP 5: Verify install + clear Gatekeeper quarantine
# ═══════════════════════════════════════════════════════════════════
echo "[5/8] Verifying install..."
if [ ! -x "$LAUNCHER" ]; then
    echo "      ERROR: $LAUNCHER not found or not executable!" >&2
    exit 1
fi
echo "      OK: $LAUNCHER"

echo "      Clearing Gatekeeper quarantine attribute..."
xattr -cr "$APP_PATH" 2>/dev/null || true
echo "      Done"

# ═══════════════════════════════════════════════════════════════════
# STEP 6: Machine-wide LaunchAgent (equivalent of Windows HKLM\Run)
# ═══════════════════════════════════════════════════════════════════
echo "[6/8] Writing machine-wide LaunchAgent..."
mkdir -p "$LAUNCH_AGENT_DIR"
cat > "$LAUNCH_AGENT_FILE" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$LAUNCHER</string>
        <string>--watchdog</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>ProcessType</key>
    <string>Interactive</string>
    <key>ThrottleInterval</key>
    <integer>10</integer>
    <key>StandardOutPath</key>
    <string>/tmp/activepulse-stdout.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/activepulse-stderr.log</string>
</dict>
</plist>
EOF
chmod 644 "$LAUNCH_AGENT_FILE"
echo "      Wrote: $LAUNCH_AGENT_FILE"

# ═══════════════════════════════════════════════════════════════════
# STEP 7: Load the LaunchAgent NOW for the console (logged-in) user
# ═══════════════════════════════════════════════════════════════════
echo "[7/8] Launching agent for the current console user..."
console_user=$(stat -f%Su /dev/console 2>/dev/null)

if [ -z "$console_user" ] || [ "$console_user" = "root" ]; then
    echo "      No graphical console user detected right now."
    echo "      The LaunchAgent is installed — the agent will start on next login."
else
    uid=$(id -u "$console_user" 2>/dev/null)
    echo "      Console user: $console_user (uid $uid)"

    # Reset any stale bootstrap from a previous install, then bootstrap fresh.
    launchctl bootout "gui/$uid" "$LAUNCH_AGENT_FILE" 2>/dev/null || true
    launchctl bootstrap "gui/$uid" "$LAUNCH_AGENT_FILE" 2>/dev/null
    if [ $? -ne 0 ]; then
        echo "      launchctl bootstrap failed; falling back to direct launch via 'open'."
        sudo -u "$console_user" open -a "$APP_PATH" --args --watchdog
    else
        echo "      LaunchAgent bootstrapped into gui/$uid (RunAtLoad triggers launch now)."
    fi
fi

sleep 5

# ═══════════════════════════════════════════════════════════════════
# STEP 8: Verify logs were created + print summary
# ═══════════════════════════════════════════════════════════════════
echo "[8/8] Verifying logs..."
found_logs=false
for home_dir in /Users/*; do
    [ -d "$home_dir" ] || continue
    logs_dir="$home_dir/Library/Application Support/ActivePulse/logs"
    if [ -d "$logs_dir" ] && [ -n "$(ls -A "$logs_dir" 2>/dev/null)" ]; then
        echo "      Logs found: $logs_dir"
        found_logs=true
    fi
done
if [ "$found_logs" = false ]; then
    echo "      No log files found yet. Check again shortly:"
    echo "      ls ~/Library/Application\\ Support/ActivePulse/logs"
    echo "      Also check launchd's own stdout/stderr (not the app's logger):"
    echo "      /tmp/activepulse-stdout.log and /tmp/activepulse-stderr.log"
fi

echo ""
echo "==============================================================="
echo "  Summary"
echo "==============================================================="
echo "  App:         $APP_PATH"
echo "  LaunchAgent: $LAUNCH_AGENT_FILE (machine-wide, all users)"
echo "  Logs:        ~/Library/Application Support/ActivePulse/logs (per user)"
echo "==============================================================="
