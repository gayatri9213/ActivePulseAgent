@echo off
setlocal

set APP_NAME=ActivePulse
set APP_VERSION=1.0.0
set MAIN_CLASS=com.activepulse.agent.ActivePulseApplication
set JAR_NAME=active-pulse-0.0.1-SNAPSHOT.jar
set JAR_FILE=target\%JAR_NAME%
set BUILD_DIR=C:\ap-build
set INPUT_DIR=%BUILD_DIR%\input
set OUT_DIR=%BUILD_DIR%\output

:: ── Force short TEMP so WiX internal paths stay short ─────────────────
set TEMP=C:\ap-tmp
set TMP=C:\ap-tmp
mkdir "C:\ap-tmp" 2>nul

echo.
echo ╔══════════════════════════════════════════════════╗
echo ║   ActivePulse — Full Windows Installer (.exe)    ║
echo ╚══════════════════════════════════════════════════╝
echo.

:: ── Step 0: Kill any process locking the JAR ─────────────────────────
echo [INFO] Stopping any running ActivePulse or Java instances...
taskkill /f /im ActivePulse.exe >nul 2>&1
taskkill /f /im javaw.exe       >nul 2>&1
taskkill /f /im java.exe        >nul 2>&1
timeout /t 2 /nobreak >nul
echo [OK] Processes cleared.

:: ── Step 1: Checks ────────────────────────────────────────────────────
if not exist "%JAR_FILE%" (
    echo [ERROR] JAR not found. Run: mvn clean package -q
    pause & exit /b 1
)
echo [OK] JAR found.

where jpackage >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] jpackage not found. Need JDK 17+.
    pause & exit /b 1
)
echo [OK] jpackage found.

where candle >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] WiX Toolset not found.
    echo         Download: https://github.com/wixtoolset/wix3/releases
    echo         After install, restart this terminal and try again.
    pause & exit /b 1
)
echo [OK] WiX Toolset found.

:: ── Step 2: Clean short build dirs ───────────────────────────────────
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%INPUT_DIR%" & mkdir "%OUT_DIR%"

:: ── Step 3: Copy JAR to short path ───────────────────────────────────
copy /Y "%JAR_FILE%" "%INPUT_DIR%\%JAR_NAME%" >nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Cannot copy JAR — still locked. Close IntelliJ/IDE and retry.
    pause & exit /b 1
)
echo [OK] JAR copied to short path.

:: ── Step 4: Icon ──────────────────────────────────────────────────────
set ICON_ARG=
if exist "src\main\resources\tray-icon.ico" (
    copy /Y "src\main\resources\tray-icon.ico" "%BUILD_DIR%\tray-icon.ico" >nul
    set ICON_ARG=--icon %BUILD_DIR%\tray-icon.ico
    echo [OK] tray-icon.ico found.
) else (
    echo [WARN] No tray-icon.ico found — using default Java icon.
)

:: ── Step 5: License ───────────────────────────────────────────────────
set LICENSE_ARG=
if exist "LICENSE.txt" (
    copy /Y "LICENSE.txt" "%BUILD_DIR%\LICENSE.txt" >nul
    set LICENSE_ARG=--license-file %BUILD_DIR%\LICENSE.txt
)

:: ── Step 6: Build ─────────────────────────────────────────────────────
echo.
echo [INFO] Running jpackage --type exe (5-10 min, please wait)...
echo.

if exist "target\setup" rmdir /s /q "target\setup"
mkdir "target\setup"

jpackage ^
  --type exe ^
  --name "%APP_NAME%" ^
  --app-version "%APP_VERSION%" ^
  --description "Service Process" ^
  --vendor "Aress Software" ^
  --input "%INPUT_DIR%" ^
  --main-jar "%JAR_NAME%" ^
  --main-class "%MAIN_CLASS%" ^
  --dest "target\setup" ^
  --temp "%BUILD_DIR%\jpackage-tmp" ^
  --java-options "-Djava.awt.headless=false" ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Xms64m" ^
  --java-options "-Xmx256m" ^
  --java-options "-Duser.timezone=Asia/Kolkata" ^
  %ICON_ARG% ^
  %LICENSE_ARG% ^
  --win-dir-chooser ^
  --win-menu ^
  --win-menu-group "ActivePulse" ^
  --win-shortcut ^
  --win-shortcut-prompt ^
  --win-per-user-install ^
  --win-upgrade-uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] jpackage failed. Check output above.
    rmdir /s /q "%BUILD_DIR%" 2>nul
    rmdir /s /q "C:\ap-tmp"   2>nul
    pause & exit /b 1
)

:: ── Step 7: Cleanup ───────────────────────────────────────────────────
rmdir /s /q "%BUILD_DIR%" 2>nul
rmdir /s /q "C:\ap-tmp"   2>nul

echo.
echo ╔══════════════════════════════════════════════════╗
echo ║  Build Complete!                                 ║
echo ╚══════════════════════════════════════════════════╝
echo.
echo   Installer: target\setup\ActivePulse-%APP_VERSION%.exe
echo.
echo   Installs with:
echo     - Setup wizard + directory chooser
echo     - Start Menu under 'ActivePulse'
echo     - Add/Remove Programs entry
echo     - Bundled JRE (no Java needed on target)
echo.

set /p OPEN="Open output folder? (y/n): "
if /i "%OPEN%"=="y" explorer "target\setup"

endlocal
pause