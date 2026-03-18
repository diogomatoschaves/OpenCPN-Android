#!/bin/bash
# Extract native libraries and Qt jars from an existing OpenCPN APK.
#
# Data assets (s57data, gshhs, locales, SVGs, etc.) are already committed
# to this repo (extracted from the macOS build). This script only extracts
# the Android-specific binaries that can't come from macOS:
#   - Native .so libraries (libgorp.so, Qt5 libs, plugins)
#   - Qt .jar files
#   - Plugin .so binaries
#
# Usage: ./extract-apk-assets.sh /path/to/opencpn.apk
#
# How to obtain the APK:
#   Option 1: On a device with OpenCPN installed:
#     adb shell pm path org.opencpn.opencpn
#     adb pull <path_from_above> opencpn.apk
#
#   Option 2: Use an APK extractor app on an Android device
#
#   Option 3: Use Aurora Store with an anonymous Google account

set -euo pipefail

APK_PATH="${1:?Usage: $0 /path/to/opencpn.apk}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP_DIR=$(mktemp -d)

echo "Extracting APK to $TMP_DIR..."
unzip -q "$APK_PATH" -d "$TMP_DIR"

# 1. Extract native libraries (jniLibs)
echo ""
echo "=== Native Libraries ==="
for arch in arm64-v8a armeabi-v7a; do
    src="$TMP_DIR/lib/$arch"
    dest="$SCRIPT_DIR/app/src/main/jniLibs/$arch"
    if [ -d "$src" ]; then
        mkdir -p "$dest"
        count=$(ls "$src/"*.so 2>/dev/null | wc -l)
        cp "$src/"*.so "$dest/"
        echo "  $arch: $count libraries copied"
    else
        echo "  $arch: not found in APK"
    fi
done

# 2. Extract Qt jars
echo ""
echo "=== Qt Jars ==="
mkdir -p "$SCRIPT_DIR/app/libs"
jar_count=0
# Check common locations for jars in the APK
for jar_dir in "$TMP_DIR/main" "$TMP_DIR/assets" "$TMP_DIR"; do
    if [ -d "$jar_dir" ]; then
        while IFS= read -r jar; do
            cp "$jar" "$SCRIPT_DIR/app/libs/"
            echo "  Copied: $(basename "$jar")"
            jar_count=$((jar_count + 1))
        done < <(find "$jar_dir" -name "*.jar" 2>/dev/null)
    fi
done
if [ "$jar_count" -eq 0 ]; then
    echo "  No jars found (may be embedded in classes.dex)"
fi

# 3. Extract plugin .so binaries (stored in assets, not lib/)
echo ""
echo "=== Plugin Binaries ==="
plugin_assets="$TMP_DIR/assets/files/plugins"
dest_plugins="$SCRIPT_DIR/app/src/main/assets/files/plugins"
if [ -d "$plugin_assets" ]; then
    plugin_count=0
    while IFS= read -r so_file; do
        rel_path="${so_file#$plugin_assets/}"
        dest_file="$dest_plugins/$rel_path"
        mkdir -p "$(dirname "$dest_file")"
        cp "$so_file" "$dest_file"
        echo "  Copied: $rel_path"
        plugin_count=$((plugin_count + 1))
    done < <(find "$plugin_assets" -name "*.so*" -type f 2>/dev/null)
    echo "  Total: $plugin_count plugin binaries"
else
    echo "  No plugin binaries found in APK assets"
fi

# 4. Extract Qt deployment assets if present
echo ""
echo "=== Qt Deployment Assets ==="
qt_assets="$TMP_DIR/assets/--Added-by-androiddeployqt--"
if [ -d "$qt_assets" ]; then
    dest_qt="$SCRIPT_DIR/app/src/main/assets/--Added-by-androiddeployqt--"
    mkdir -p "$dest_qt"
    cp -r "$qt_assets/"* "$dest_qt/"
    echo "  Copied Qt deployment assets"
else
    echo "  No Qt deployment assets found"
fi

# Cleanup
rm -rf "$TMP_DIR"

echo ""
echo "================================================"
echo "Done! You can now build with: ./gradlew assembleRelease"
echo "================================================"
