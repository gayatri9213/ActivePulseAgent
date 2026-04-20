
#!/bin/bash


# ─────────────────────────────────────────────────────────────────────
#  ActivePulse — macOS installer via jpackage
#  Builds BOTH .dmg (drag-to-install) AND .pkg (system installer)
#  Supports both Intel (x64) and ARM64 architectures
#
#  Usage:
#    ./package-mac.sh              # Auto-detect and build for current arch
#    ./package-mac.sh x64          # Build for Intel (x64)
#    ./package-mac.sh arm64        # Build for Apple Silicon (ARM64)
#    ./package-mac.sh both         # Build for both architectures
#
#  chmod +x package-mac.sh && ./package-mac.sh
# ─────────────────────────────────────────────────────────────────────

APP_NAME="ActivePulse"
APP_VERSION="1.0.0"
MAIN_CLASS="com.activepulse.agent.ActivePulseApplication"
JAR_NAME="active-pulse-0.0.1-SNAPSHOT.jar"
JAR_FILE="target/$JAR_NAME"
BUILD_DIR="/tmp/ap-build"
OUT_DIR="target/setup"
BUNDLE_ID="com.aress.activepulse"

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
TARGET_ARCH="${1:-$CURRENT_ARCH}"

# Validate architecture
if [[ "$TARGET_ARCH" != "x64" && "$TARGET_ARCH" != "arm64" && "$TARGET_ARCH" != "both" ]]; then
    echo "[ERROR] Invalid architecture: $TARGET_ARCH. Use: x64, arm64, or both"
    exit 1
fi

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║   ActivePulse — macOS Installer                  ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "[INFO] Current architecture: $CURRENT_ARCH"
echo "[INFO] Target architecture: $TARGET_ARCH"
echo ""

# ── Checks ────────────────────────────────────────────────────────────
[ ! -f "$JAR_FILE" ] && echo "[ERROR] JAR not found. Run: mvn clean package -q" && exit 1
command -v jpackage &>/dev/null || { echo "[ERROR] jpackage not found. Need JDK 17+."; exit 1; }

# ── Build function for specific architecture ─────────────────────────────
build_for_arch() {
    local arch=$1
    local arch_suffix=""
    
    if [[ "$arch" == "x64" ]]; then
        arch_suffix="-x64"
    elif [[ "$arch" == "arm64" ]]; then
        arch_suffix="-arm64"
    fi
    
    local app_name_with_arch="${APP_NAME}${arch_suffix}"
    local out_dir_arch="$OUT_DIR/$arch"
    
    echo ""
    echo "═══════════════════════════════════════════════════"
    echo "  Building for architecture: $arch"
    echo "═══════════════════════════════════════════════════"
    echo ""
    
    # Check if current architecture matches target
    if [[ "$CURRENT_ARCH" != "$arch" ]]; then
        echo "[WARN] Current architecture ($CURRENT_ARCH) does not match target ($arch)"
        echo "[WARN] You must run this script on a $arch Mac to build for $arch"
        echo "[WARN] Or use Rosetta 2 (for x64 on ARM64) - not recommended"
        echo ""
        read -p "Continue anyway? (y/N) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            echo "[SKIP] Skipping $arch build"
            return 1
        fi
    fi
    
    # Prepare build directory for this architecture
    local build_dir_arch="${BUILD_DIR}-${arch}"
    local input_dir_arch="$build_dir_arch/input"
    rm -rf "$build_dir_arch" && mkdir -p "$input_dir_arch" "$out_dir_arch"
    cp "$JAR_FILE" "$input_dir_arch/$JAR_NAME"
    
    # Convert PNG → ICNS for macOS
    local icon_arg=""
    if [ -f "src/main/resources/tray-icon.png" ]; then
        mkdir -p "$build_dir_arch/icon.iconset"
        for size in 16 32 64 128 256 512; do
            sips -z $size $size "src/main/resources/tray-icon.png" \
                 --out "$build_dir_arch/icon.iconset/icon_${size}x${size}.png" 2>/dev/null
        done
        iconutil -c icns "$build_dir_arch/icon.iconset" -o "$build_dir_arch/tray-icon.icns" 2>/dev/null
        [ -f "$build_dir_arch/tray-icon.icns" ] && icon_arg="--icon $build_dir_arch/tray-icon.icns" && echo "[OK] tray-icon.png converted to .icns"
    fi
    
    local license_arg=""
    [ -f "LICENSE.txt" ] && cp "LICENSE.txt" "$build_dir_arch/LICENSE.txt" \
        && license_arg="--license-file $build_dir_arch/LICENSE.txt"
    
    # Common jpackage args for this architecture
    local common_args=(
        --name "$app_name_with_arch"
        --app-version "$APP_VERSION"
        --description "Service Process"
        --vendor "Aress Software"
        --input "$input_dir_arch"
        --main-jar "$JAR_NAME"
        --main-class "$MAIN_CLASS"
        --java-options "-Djava.awt.headless=false"
        --java-options "-Dfile.encoding=UTF-8"
        --java-options "-Xms64m"
        --java-options "-Xmx256m"
        --java-options "-Duser.timezone=Asia/Kolkata"
        --mac-package-identifier "$BUNDLE_ID"
        --mac-package-name "$app_name_with_arch"
    )
    [ -n "$icon_arg" ]    && common_args+=($icon_arg)
    [ -n "$license_arg" ] && common_args+=($license_arg)
    
    # ── Build .dmg (drag to Applications) ─────────────────────────────────
    echo "[1/2] Building .dmg for $arch..."
    jpackage --type dmg "${common_args[@]}" --dest "$out_dir_arch"
    if [ $? -ne 0 ]; then
        echo "[ERROR] .dmg build failed for $arch."
        rm -rf "$build_dir_arch"
        return 1
    fi
    echo "[OK] .dmg created for $arch."
    
    # ── Build .pkg (system installer with wizard) ──────────────────────────
    echo "[2/2] Building .pkg for $arch..."
    jpackage --type pkg "${common_args[@]}" --dest "$out_dir_arch"
    if [ $? -ne 0 ]; then
        echo "[WARN] .pkg build failed for $arch — .dmg is still available."
    fi
    
    rm -rf "$build_dir_arch"
    echo "[OK] Build completed for $arch"
    return 0
}

# ── Build for target architecture(s) ────────────────────────────────────
if [[ "$TARGET_ARCH" == "both" ]]; then
    echo "[INFO] Building for both Intel (x64) and ARM64 architectures..."
    echo ""
    
    # Build for x64
    build_for_arch "x64"
    x64_success=$?
    
    # Build for arm64
    build_for_arch "arm64"
    arm64_success=$?
    
    # Summary
    echo ""
    echo "═══════════════════════════════════════════════════"
    echo "  Build Summary"
    echo "═══════════════════════════════════════════════════"
    echo ""
    echo "  Intel (x64):    $([ $x64_success -eq 0 ] && echo "SUCCESS" || echo "FAILED")"
    echo "  ARM64:          $([ $arm64_success -eq 0 ] && echo "SUCCESS" || echo "FAILED")"
    echo ""
    
    # List all built packages
    echo "Built packages:"
    ls -lh "$OUT_DIR/"*/ 2>/dev/null || echo "No packages built"
    echo ""
else
    # Build for single architecture
    build_for_arch "$TARGET_ARCH"
    build_success=$?
    
    if [ $build_success -eq 0 ]; then
        local out_dir_arch="$OUT_DIR/$TARGET_ARCH"
        echo ""
        echo "═══════════════════════════════════════════════════"
        echo "  Done!                                           "
        echo "═══════════════════════════════════════════════════"
        echo ""
        ls -lh "$out_dir_arch/"
        echo ""
        echo "  .dmg — User drags app to Applications folder"
        echo "  .pkg — Full installer wizard (recommended for enterprise)"
    else
        echo ""
        echo "[ERROR] Build failed for $TARGET_ARCH"
        exit 1
    fi
fi