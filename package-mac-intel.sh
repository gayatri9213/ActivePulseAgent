#!/bin/bash

# ─────────────────────────────────────────────────────────────────────
#  ActivePulse — macOS Intel (x64) installer via jpackage
#  Builds BOTH .dmg (drag-to-install) AND .pkg (system installer)
#  Specifically for Intel Macs (x64 architecture)
#
#  Usage:
#    ./package-mac-intel.sh
#
#  chmod +x package-mac-intel.sh && ./package-mac-intel.sh
# ─────────────────────────────────────────────────────────────────────

APP_NAME="ActivePulse"
APP_VERSION="1.0.0"
MAIN_CLASS="com.activepulse.agent.ActivePulseApplication"
JAR_NAME="active-pulse-0.0.1-SNAPSHOT.jar"
JAR_FILE="target/$JAR_NAME"
BUILD_DIR="/tmp/ap-build-intel"
OUT_DIR="target/setup"
BUNDLE_ID="com.aress.activepulse"

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║   ActivePulse — macOS Intel (x64) Installer      ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ── Architecture Detection ─────────────────────────────────────────────
detect_arch() {
    local arch=$(uname -m)
    if [[ "$arch" == "x86_64" ]]; then
        echo "x64"
    elif [[ "$arch" == "arm64" ]]; then
        echo "arm64"
    else
        echo "unknown"
    fi
}

CURRENT_ARCH=$(detect_arch)
TARGET_ARCH="x64"

echo "[INFO] Current architecture: $CURRENT_ARCH"
echo "[INFO] Target architecture: $TARGET_ARCH (Intel Mac)"
echo ""

# ── Checks ────────────────────────────────────────────────────────────
[ ! -f "$JAR_FILE" ] && echo "[ERROR] JAR not found. Run: mvn clean package -q" && exit 1
command -v jpackage &>/dev/null || { echo "[ERROR] jpackage not found. Need JDK 17+."; exit 1; }

# Check if running on Intel Mac
if [[ "$CURRENT_ARCH" != "x64" ]]; then
    echo "[WARN] Current architecture ($CURRENT_ARCH) is not Intel (x64)"
    echo "[WARN] This script is specifically for Intel Macs"
    echo "[WARN] If you're on Apple Silicon, use package-mac.sh instead"
    echo ""
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "[SKIP] Exiting - use package-mac.sh for Apple Silicon"
        exit 1
    fi
fi

# ── Build for Intel (x64) ───────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════"
echo "  Building for Intel Mac (x64)"
echo "═══════════════════════════════════════════════════"
echo ""

# Prepare build directory
INPUT_DIR="$BUILD_DIR/input"
rm -rf "$BUILD_DIR" && mkdir -p "$INPUT_DIR" "$OUT_DIR"
cp "$JAR_FILE" "$INPUT_DIR/$JAR_NAME"

# Convert PNG → ICNS for macOS
ICON_ARG=""
if [ -f "src/main/resources/tray-icon.png" ]; then
    mkdir -p "$BUILD_DIR/icon.iconset"
    for size in 16 32 64 128 256 512; do
        sips -z $size $size "src/main/resources/tray-icon.png" \
             --out "$BUILD_DIR/icon.iconset/icon_${size}x${size}.png" 2>/dev/null
    done
    iconutil -c icns "$BUILD_DIR/icon.iconset" -o "$BUILD_DIR/tray-icon.icns" 2>/dev/null
    [ -f "$BUILD_DIR/tray-icon.icns" ] && ICON_ARG="--icon $BUILD_DIR/tray-icon.icns" && echo "[OK] tray-icon.png converted to .icns"
fi

LICENSE_ARG=""
[ -f "LICENSE.txt" ] && cp "LICENSE.txt" "$BUILD_DIR/LICENSE.txt" \
    && LICENSE_ARG="--license-file $BUILD_DIR/LICENSE.txt"

# Common jpackage args
COMMON_ARGS=(
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --description "Service Process"
    --vendor "Aress Software"
    --input "$INPUT_DIR"
    --main-jar "$JAR_NAME"
    --main-class "$MAIN_CLASS"
    --java-options "-Djava.awt.headless=false"
    --java-options "-Dfile.encoding=UTF-8"
    --java-options "-Xms64m"
    --java-options "-Xmx256m"
    --java-options "-Duser.timezone=Asia/Kolkata"
    --mac-package-identifier "$BUNDLE_ID"
    --mac-package-name "$APP_NAME"
)
[ -n "$ICON_ARG" ]    && COMMON_ARGS+=($ICON_ARG)
[ -n "$LICENSE_ARG" ] && COMMON_ARGS+=($LICENSE_ARG)

# ── Build .dmg (drag to Applications) ─────────────────────────────────
echo "[1/2] Building .dmg for Intel Mac..."
jpackage --type dmg "${COMMON_ARGS[@]}" --dest "$OUT_DIR"
if [ $? -ne 0 ]; then
    echo "[ERROR] .dmg build failed."
    rm -rf "$BUILD_DIR"
    exit 1
fi
echo "[OK] .dmg created for Intel Mac."

# ── Build .pkg (system installer with wizard) ──────────────────────────
echo "[2/2] Building .pkg for Intel Mac..."
jpackage --type pkg "${COMMON_ARGS[@]}" --dest "$OUT_DIR"
if [ $? -ne 0 ]; then
    echo "[WARN] .pkg build failed — .dmg is still available."
fi

rm -rf "$BUILD_DIR"

echo ""
echo "═══════════════════════════════════════════════════"
echo "  Done!                                           "
echo "═══════════════════════════════════════════════════"
echo ""
ls -lh "$OUT_DIR/"
echo ""
echo "  .dmg — User drags app to Applications folder"
echo "  .pkg — Full installer wizard (recommended for enterprise)"
echo ""
echo "  Both packages are for Intel Macs (x64) only"
