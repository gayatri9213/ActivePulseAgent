# ActivePulse Agent

Cross-platform background activity monitoring agent for AD-managed environments.

Built with **Java 21 + Maven + SQLite**. Runs silently per-user after a machine-wide install. Tracks active applications, window titles, keyboard/mouse activity, and screenshots. Syncs to a configurable server endpoint.

---

## Feature summary

| Feature                                | Status                                         |
| -------------------------------------- | ---------------------------------------------- |
| Active window / process tracking       | ✅ Windows (JNA), macOS (osascript), Linux (xdotool) |
| Keyboard / mouse activity              | ✅ via JNativeHook                              |
| Screenshot capture                     | ✅ java.awt.Robot, JPG quality configurable     |
| SQLite local storage                   | ✅ WAL mode, schema migrations                  |
| Sync to server (JSON + multipart ZIP)  | ✅ supports ≤10 MB single + chunked > 10 MB     |
| AD user detection (not installer Admin)| ✅ WTS primary, explorer.exe fallback           |
| Machine-wide install                   | ✅ jpackage .msi / .deb / .pkg                  |
| Per-user autostart                     | ✅ HKCU (Win), LaunchAgent (mac), XDG (Linux)   |
| Single-instance guard                  | ✅ per-user file lock                           |
| Idle / active status tracking          | ✅ configurable threshold                       |

---

## Project structure

```
activepulse-agent/
├── .github/workflows/build-installers.yml   # 4-platform CI
├── pom.xml
├── src/main/
│   ├── java/com/activepulse/agent/
│   │   ├── Main.java                        # entry point
│   │   ├── SingleInstanceLock.java
│   │   ├── db/                              # DatabaseManager + DAOs
│   │   ├── monitor/                         # window/activity/user status
│   │   ├── screenshot/
│   │   ├── sync/                            # SyncManager (server API)
│   │   ├── job/                             # Quartz jobs + scheduler
│   │   ├── autostart/                       # per-OS autostart
│   │   ├── platform/
│   │   │   ├── windows/                     # WTS user resolver, active window
│   │   │   ├── macos/
│   │   │   └── linux/
│   │   └── util/                            # EnvConfig, paths, time, OS
│   ├── resources/
│   │   ├── logback.xml
│   │   └── agent.env.template
│   └── sql/schema.sql
└── README.md
```

---

## Build

Requires **JDK 21** and **Maven 3.9+**.

```bash
mvn clean package
```

Produces `target/activepulse-agent-1.0.0.jar` — a self-contained fat JAR.

---

## Run (development)

```bash
java -jar target/activepulse-agent-1.0.0.jar
```

Config precedence (first match wins):
1. `-Dactivepulse.env=/path/to/agent.env` system property
2. `./agent.env` in current directory
3. `agent.env` next to the running JAR
4. Bundled `agent.env.template` defaults

Real environment variables **override** the file.

---

## Configuration

Copy `src/main/resources/agent.env.template` to `agent.env` and fill in:

```properties
SERVER_BASE_URL=https://api.example.com
API_KEY=your-bearer-token
USER_ID=42
ORGANIZATION_ID=7

ACTIVITY_POLL_SECONDS=5
STROKE_AGGREGATE_SECONDS=60
SCREENSHOT_INTERVAL_SECONDS=300
SYNC_INTERVAL_SECONDS=300

ENABLE_SCREENSHOTS=true
ENABLE_KEYBOARD_MOUSE=true
ENABLE_WINDOW_TRACKING=true
ENABLE_AUTOSTART=true

IDLE_THRESHOLD_SECONDS=180
```

If `SERVER_BASE_URL` or `API_KEY` is blank, the agent still captures data but holds it in SQLite — it retries sync every cycle.

---

## Machine-wide install (the core AD scenario)

### Windows

1. Admin runs the `.msi` (from GitHub Actions artifact or release):

   ```powershell
   msiexec /i ActivePulse-1.0.0.msi /quiet
   ```

   Files install to `C:\Program Files\ActivePulse\`.

2. Admin drops `agent.env` into `C:\Program Files\ActivePulse\app\` (or wherever the JAR lives — the app searches alongside it).

3. AD user logs in. Start the app once via the Start Menu shortcut (or push via GPO logon script: `"C:\Program Files\ActivePulse\ActivePulse.exe"`).

4. On first launch the app:
   - Detects the **AD user** via Windows Terminal Services API (WTS), falling back to the owner of `explorer.exe` if WTS reports something unexpected
   - Creates per-user data directory under `%LOCALAPPDATA%\ActivePulse\`
   - Writes autostart entry to `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`
   - Starts monitoring silently

5. From the next login onwards, the app runs automatically in the background for that AD user.

**Why HKCU (not HKLM) for autostart?** HKLM autostart would run as every user including service accounts — including SYSTEM on some configurations. HKCU scopes to the actual interactive AD user, which is what you want for activity monitoring.

### macOS

1. Admin installs the `.pkg` — app placed at `/Applications/ActivePulse.app`.
2. User launches from Launchpad. App writes a LaunchAgent at `~/Library/LaunchAgents/com.aress.activepulse.plist` (user-level, not daemon).
3. Grant **Accessibility** permission (System Settings → Privacy & Security → Accessibility) for window title capture. Without it, process names still work but titles are blank.

### Linux

1. Admin installs the `.deb`:
   ```bash
   sudo dpkg -i activepulse_1.0.0_amd64.deb
   ```
2. Install xdotool for window capture:
   ```bash
   sudo apt-get install xdotool
   ```
3. User launches `/opt/activepulse/bin/ActivePulse` once. App creates `~/.config/autostart/activepulse.desktop`.

---

## Per-user data locations

| Platform | Data directory                                    |
| -------- | ------------------------------------------------- |
| Windows  | `%LOCALAPPDATA%\ActivePulse\`                     |
| macOS    | `~/Library/Application Support/ActivePulse/`      |
| Linux    | `~/.local/share/activepulse/`                     |

Contains: `activepulse.db` (SQLite), `screenshots/`, `logs/agent.log`, `device-id`, `activepulse.lock`.

These are always **per-user** — a machine-wide install with three AD users results in three independent data directories. No cross-contamination.

---

## Database schema (inspection)

```bash
# Windows
sqlite3 %LOCALAPPDATA%\ActivePulse\activepulse.db

# macOS / Linux
sqlite3 ~/Library/Application\ Support/ActivePulse/activepulse.db
```

Useful queries:

```sql
.tables
SELECT * FROM agent_config;
SELECT COUNT(*), MIN(recorded_at), MAX(recorded_at) FROM activity_log;
SELECT COUNT(*), SUM(keyboardcount), SUM(keymousecount) FROM keyboard_mouse_strokes;
SELECT * FROM sync_log ORDER BY sync_start DESC LIMIT 5;
```

---

## AD user detection — how it actually works

This is the piece that often goes wrong with "Admin runs installer, should track the logged-in user" setups. The chain in `WindowsUserResolver.java`:

1. **`WTSGetActiveConsoleSessionId` + `WTSQuerySessionInformation`** — native Windows API, works even if the app runs under SYSTEM (if you later wire it as a service). Returns the domain and username of the user physically logged in at the console.

2. **Owner of `explorer.exe`** — fallback for RDP / remote desktop where WTS may report differently than the "interactive user." PowerShell `Get-CimInstance Win32_Process` gives us the owner.

3. **`System.getProperty("user.name")`** — last resort, logged as `WARN`. If you see this source in `agent_config.userSource`, the first two approaches failed and you're monitoring whoever ran the process (possibly Admin).

Username is stored as `DOMAIN\username` for domain users, `MACHINENAME\username` for local accounts.

---

## Cross-platform CI

`.github/workflows/build-installers.yml` builds all four installers in parallel:

| Runner         | Output                                 |
| -------------- | -------------------------------------- |
| `ubuntu-latest`| `ActivePulse-Linux-x64.deb`            |
| `windows-latest` | `ActivePulse-Windows-x64.msi`        |
| `macos-13`     | `ActivePulse-macOS-Intel.pkg`          |
| `macos-14`     | `ActivePulse-macOS-AppleSilicon.pkg`   |

Trigger:
- Push to `main` / `develop` → artifacts only
- Tag `v*` → full GitHub Release with all 4 installers attached
- Manual `workflow_dispatch` → artifacts with custom version

---

## Known limitations / TODO

These are intentional gaps you'll likely need to fill for your environment:

- **Browser URL capture** (address bar) — not implemented. Windows needs UIAutomation per-browser; brittle and intrusive. Ships returning empty string in `url` field.
- **macOS signing / notarization** — the `.pkg` is unsigned. Gatekeeper will block execution on end-user machines without `codesign` + `notarytool`. Add signing to the `build-macos-*` jobs before distributing.
- **Wayland support on Linux** — `LinuxActiveWindowTracker` uses xdotool (X11 only). For Wayland you'd need platform-specific protocols (wlroots, GNOME Shell DBus, etc.).
- **Service vs user-session mode on Windows** — current design runs as the logged-in user (simpler, respects privilege boundaries). If you later need SYSTEM-level with session handoff, the WTS resolver code will carry over.
- **Screenshot privacy gate** — no pause-on-sensitive-window heuristic. Consider adding a blocklist via `agent.env`.

---

## License

Proprietary — Pink Owl Technologies Private Limited / Aress Software.
