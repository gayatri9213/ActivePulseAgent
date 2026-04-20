#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
#  ActivePulse — Linux .deb / .rpm installer via jpackage
#  Run on: Ubuntu/Debian (for .deb) or RHEL/Fedora (for .rpm)
#
#  chmod +x package-linux.sh && ./package-linux.sh
# ─────────────────────────────────────────────────────────────────────

APP_NAME="ActivePulse"
APP_VERSION="1.0.0"
MAIN_CLASS="com.activepulse.agent.ActivePulseApplication"
JAR_NAME="active-pulse-0.0.1-SNAPSHOT.jar"
JAR_FILE="target/$JAR_NAME"
BUILD_DIR="/tmp/ap-build"
INPUT_DIR="$BUILD_DIR/input"
OUT_DIR="target/setup"

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║   ActivePulse — Linux Installer                  ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ── Checks ────────────────────────────────────────────────────────────
[ ! -f "$JAR_FILE" ] && echo "[ERROR] JAR not found. Run: mvn clean package -q" && exit 1
command -v jpackage &>/dev/null || { echo "[ERROR] jpackage not found. Need JDK 17+."; exit 1; }

# Detect package type
if command -v dpkg &>/dev/null; then
    PKG_TYPE="deb"
    echo "[OK] dpkg found — building .deb"
elif command -v rpm &>/dev/null; then
    PKG_TYPE="rpm"
    echo "[OK] rpm found — building .rpm"
else
    echo "[ERROR] Neither dpkg nor rpm found. Cannot build installer."
    exit 1
fi

# ── Prepare ───────────────────────────────────────────────────────────
rm -rf "$BUILD_DIR" && mkdir -p "$INPUT_DIR" "$OUT_DIR"
cp "$JAR_FILE" "$INPUT_DIR/$JAR_NAME"

ICON_ARG=""
if [ -f "src/main/resources/tray-icon.png" ]; then
    cp "src/main/resources/tray-icon.png" "$BUILD_DIR/tray-icon.png"
    ICON_ARG="--icon $BUILD_DIR/tray-icon.png"
    echo "[OK] tray-icon.png found."
fi

LICENSE_ARG=""
if [ -f "LICENSE.txt" ]; then
    cp "LICENSE.txt" "$BUILD_DIR/LICENSE.txt"
    LICENSE_ARG="--license-file $BUILD_DIR/LICENSE.txt"
fi

echo "[INFO] Building .$PKG_TYPE installer..."

jpackage \
  --type "$PKG_TYPE" \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --description "Service Process" \
  --vendor "Aress Software" \
  --input "$INPUT_DIR" \
  --main-jar "$JAR_NAME" \
  --main-class "$MAIN_CLASS" \
  --dest "$OUT_DIR" \
  --java-options "-Djava.awt.headless=false" \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options "-Xms64m" \
  --java-options "-Xmx256m" \
  --java-options "-Duser.timezone=Asia/Kolkata" \
  $ICON_ARG \
  $LICENSE_ARG \
  --linux-shortcut \
  --linux-menu-group "Office" \
  --linux-app-category "utility" \
  --linux-deb-maintainer "support@aressindia.net"

[ $? -ne 0 ] && echo "[ERROR] jpackage failed." && exit 1

rm -rf "$BUILD_DIR"

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║  Done!                                           ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
ls -lh "$OUT_DIR/"
echo ""
if [ "$PKG_TYPE" = "deb" ]; then
    echo "Install:   sudo dpkg -i $OUT_DIR/*.deb"
    echo "Uninstall: sudo dpkg -r activepulse"
else
    echo "Install:   sudo rpm -i $OUT_DIR/*.rpm"
    echo "Uninstall: sudo rpm -e activepulse"
fi