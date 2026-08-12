#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# ActivePulse — Linux Installation Script (v1.0.0)
#
# THIS SCRIPT IS LINUX-ONLY. It does not touch Windows or macOS in any
# way and has no effect on the Windows installer/PowerShell script.
#
# USAGE:
#   1. Copy the ActivePulse .deb next to this script (or into ~/Downloads,
#      /tmp, or a "dist" folder next to this script).
#   2. sudo bash install-activepulse-agent-v1_0_0-updated-linux.sh
#
# What it does (mirrors the Windows installer's approach):
#   1.  Kills ALL ActivePulse/java-activepulse processes (all users)
#   2.  Removes stale lock files (all users)
#   3.  Locates the .deb package
#   4.  Installs it with apt/dpkg (resolves dependencies)
#   5.  Verifies /opt/activepulse/bin/ActivePulse exists
#   6.  Writes a MACHINE-WIDE autostart entry at
#         /etc/xdg/autostart/activepulse.desktop
#      This is the Linux equivalent of Windows' HKLM\Run: every user who
#      logs into a graphical (XDG-compliant) desktop session — GNOME,
#      KDE, XFCE, etc. — will have ActivePulse started for them
#      automatically, without the app needing to run once first.
#   7.  Launches the agent NOW for every currently logged-in desktop user
#       (so logs/data appear immediately and you don't have to reboot to
#       verify the install worked).
#   8.  Verifies logs actually got created and prints a summary.
#
# ROOT CAUSE THIS FIXES:
#   The jpackage .deb only copies files to /opt/activepulse and adds a
#   menu shortcut — it never launches the app and never installs any
#   autostart entry. Nothing ever runs the JVM, so the per-user log/data
#   directories (~/.local/share/activepulse/logs) are never created and
#   the agent never syncs. This script is the missing piece.
# ═══════════════════════════════════════════════════════════════════
set -u

INSTALL_DIR="/opt/activepulse"
LAUNCHER="$INSTALL_DIR/bin/ActivePulse"
AUTOSTART_DIR="/etc/xdg/autostart"
AUTOSTART_FILE="$AUTOSTART_DIR/activepulse.desktop"

# ─── Must run as root ──────────────────────────────────────────────
if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: Must run as root (sudo)." >&2
    exit 1
fi

echo ""
echo "==============================================================="
echo "  ActivePulse v1.0.0 - Linux Install"
echo "==============================================================="
echo ""

# ═══════════════════════════════════════════════════════════════════
# STEP 1: Kill ALL ActivePulse processes (all users)
# ═══════════════════════════════════════════════════════════════════
echo "[1/8] Killing all ActivePulse processes (across all users)..."
pkill -f "$LAUNCHER" 2>/dev/null || true
pkill -f "bin/ActivePulse" 2>/dev/null || true
pkill -f "activepulse.*\.jar" 2>/dev/null || true
sleep 2
echo "      Done"

# ═══════════════════════════════════════════════════════════════════
# STEP 2: Clean stale lock files (all users)
# ═══════════════════════════════════════════════════════════════════
echo "[2/8] Cleaning stale lock files (all users)..."
lock_count=0
for home_dir in /home/* /root; do
    [ -d "$home_dir" ] || continue
    for data_dir in "$home_dir/.local/share/activepulse" "$home_dir/.local/share/activepulse-test"; do
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
# STEP 3: Locate the .deb package
# ═══════════════════════════════════════════════════════════════════
echo "[3/8] Locating ActivePulse .deb..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEB=""
search_dirs=("$SCRIPT_DIR" "$SCRIPT_DIR/dist" "/tmp" "/root/Downloads")
for home_dir in /home/*; do
    [ -d "$home_dir/Downloads" ] && search_dirs+=("$home_dir/Downloads")
done

newest_time=0
for dir in "${search_dirs[@]}"; do
    [ -d "$dir" ] || continue
    while IFS= read -r -d '' f; do
        mtime=$(stat -c %Y "$f" 2>/dev/null || echo 0)
        if [ "$mtime" -gt "$newest_time" ]; then
            newest_time=$mtime
            DEB="$f"
        fi
    done < <(find "$dir" -maxdepth 2 -iname "activepulse*.deb" -print0 2>/dev/null)
done

if [ -z "$DEB" ]; then
    echo "      ERROR: No .deb found. Place ActivePulse*.deb next to this script, in /tmp, or in a Downloads folder." >&2
    exit 1
fi
echo "      Found: $DEB"

# ═══════════════════════════════════════════════════════════════════
# STEP 4: Install the package
# ═══════════════════════════════════════════════════════════════════
echo "[4/8] Installing $DEB ..."
if command -v apt-get >/dev/null 2>&1; then
    apt-get install -y "$DEB" || {
        echo "      apt-get install failed, retrying with dpkg + apt-get -f ..."
        dpkg -i "$DEB"
        apt-get install -f -y
    }
else
    dpkg -i "$DEB"
fi

if ! dpkg -s activepulse >/dev/null 2>&1; then
    echo "      WARNING: dpkg does not report 'activepulse' as installed; continuing to verify files directly." 
fi
echo "      Install step complete"

# ═══════════════════════════════════════════════════════════════════
# STEP 5: Verify install
# ═══════════════════════════════════════════════════════════════════
echo "[5/8] Verifying install..."
if [ ! -x "$LAUNCHER" ]; then
    echo "      ERROR: $LAUNCHER not found or not executable!" >&2
    exit 1
fi
echo "      OK: $LAUNCHER"

# ═══════════════════════════════════════════════════════════════════
# STEP 6: Machine-wide autostart (equivalent of Windows HKLM\Run)
# ═══════════════════════════════════════════════════════════════════
echo "[6/8] Writing machine-wide autostart entry..."
mkdir -p "$AUTOSTART_DIR"
cat > "$AUTOSTART_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=ActivePulse Agent
Exec=$LAUNCHER --watchdog
Icon=activepulse
Terminal=false
X-GNOME-Autostart-enabled=true
NoDisplay=true
Hidden=false
Comment=Background activity monitor
Categories=Utility;
EOF
chmod 644 "$AUTOSTART_FILE"
echo "      Wrote: $AUTOSTART_FILE"

# ═══════════════════════════════════════════════════════════════════
# STEP 7: Launch agent NOW for every logged-in desktop user
# ═══════════════════════════════════════════════════════════════════
echo "[7/8] Launching agent for currently logged-in desktop user(s)..."
launched_any=false

# Enumerate graphical sessions via loginctl when available (systemd-logind).
if command -v loginctl >/dev/null 2>&1; then
    while IFS= read -r session_id; do
        [ -z "$session_id" ] && continue
        session_user=$(loginctl show-session "$session_id" -p Name --value 2>/dev/null)
        session_type=$(loginctl show-session "$session_id" -p Type --value 2>/dev/null)
        [ -z "$session_user" ] && continue
        [ "$session_user" = "root" ] && continue
        uid=$(id -u "$session_user" 2>/dev/null) || continue
        runtime_dir="/run/user/$uid"

        # Best-effort DISPLAY detection (X11); Wayland sessions still get
        # XDG_RUNTIME_DIR + DBUS which is enough for most tray/background apps.
        display=$(loginctl show-session "$session_id" -p Display --value 2>/dev/null)
        [ -z "$display" ] && display=":0"

        echo "      Starting for user '$session_user' (session $session_id, $session_type)..."
        sudo -u "$session_user" env \
            DISPLAY="$display" \
            XDG_RUNTIME_DIR="$runtime_dir" \
            DBUS_SESSION_BUS_ADDRESS="unix:path=$runtime_dir/bus" \
            setsid nohup "$LAUNCHER" --watchdog >/dev/null 2>&1 &
        disown
        launched_any=true
    done < <(loginctl list-sessions --no-legend 2>/dev/null | awk '{print $1}')
fi

# Fallback: use `who` if loginctl found nothing (e.g. minimal systems)
if [ "$launched_any" = false ]; then
    while IFS= read -r who_user; do
        [ -z "$who_user" ] && continue
        [ "$who_user" = "root" ] && continue
        uid=$(id -u "$who_user" 2>/dev/null) || continue
        runtime_dir="/run/user/$uid"
        echo "      Starting for user '$who_user' (via who)..."
        sudo -u "$who_user" env \
            DISPLAY=":0" \
            XDG_RUNTIME_DIR="$runtime_dir" \
            setsid nohup "$LAUNCHER" --watchdog >/dev/null 2>&1 &
        disown
        launched_any=true
    done < <(who | awk '{print $1}' | sort -u)
fi

if [ "$launched_any" = false ]; then
    echo "      No graphical desktop session detected right now."
    echo "      The autostart entry is installed — the agent will start on next login/reboot."
fi

sleep 5

# ═══════════════════════════════════════════════════════════════════
# STEP 8: Verify logs were created + print summary
# ═══════════════════════════════════════════════════════════════════
echo "[8/8] Verifying logs..."
found_logs=false
for home_dir in /home/*; do
    [ -d "$home_dir" ] || continue
    logs_dir="$home_dir/.local/share/activepulse/logs"
    if [ -d "$logs_dir" ] && [ -n "$(ls -A "$logs_dir" 2>/dev/null)" ]; then
        echo "      Logs found: $logs_dir"
        found_logs=true
    fi
done
if [ "$found_logs" = false ]; then
    echo "      No log files found yet. If a desktop session was just started, check again"
    echo "      in a few seconds: ls ~/.local/share/activepulse/logs"
fi

echo ""
echo "==============================================================="
echo "  Summary"
echo "==============================================================="
echo "  Launcher:   $LAUNCHER"
echo "  Autostart:  $AUTOSTART_FILE (machine-wide, all users)"
echo "  Logs:       ~/.local/share/activepulse/logs (per user)"
echo "==============================================================="
