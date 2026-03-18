#!/bin/bash
# Extract native libraries and assets from an existing OpenCPN APK
# Usage: ./extract-apk-assets.sh /path/to/opencpn.apk
#
# This script extracts the prebuilt native libraries (libgorp.so, Qt5, plugins)
# and data assets (charts, locales, SVGs, sounds, etc.) from an existing APK
# and places them in the correct directories for building.
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

# Extract native libraries
echo "Copying native libraries..."
if [ -d "$TMP_DIR/lib/arm64-v8a" ]; then
    cp -v "$TMP_DIR/lib/arm64-v8a/"*.so "$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a/"
    echo "  arm64-v8a: $(ls "$TMP_DIR/lib/arm64-v8a/"*.so | wc -l) libraries"
fi

if [ -d "$TMP_DIR/lib/armeabi-v7a" ]; then
    cp -v "$TMP_DIR/lib/armeabi-v7a/"*.so "$SCRIPT_DIR/app/src/main/jniLibs/armeabi-v7a/"
    echo "  armeabi-v7a: $(ls "$TMP_DIR/lib/armeabi-v7a/"*.so | wc -l) libraries"
fi

# Extract assets
echo "Copying assets..."
if [ -d "$TMP_DIR/assets/files" ]; then
    mkdir -p "$SCRIPT_DIR/app/src/main/assets/files"
    cp -rv "$TMP_DIR/assets/files/"* "$SCRIPT_DIR/app/src/main/assets/files/"
    echo "  Assets copied"
fi

# Extract Qt deployment assets
if [ -d "$TMP_DIR/assets/--Added-by-androiddeployqt--" ]; then
    mkdir -p "$SCRIPT_DIR/app/src/main/assets/--Added-by-androiddeployqt--"
    cp -rv "$TMP_DIR/assets/--Added-by-androiddeployqt--/"* "$SCRIPT_DIR/app/src/main/assets/--Added-by-androiddeployqt--/"
    echo "  Qt deployment assets copied"
fi

# Copy Qt jars if present
if [ -d "$TMP_DIR/main" ]; then
    echo "Checking for Qt jars..."
    find "$TMP_DIR/main" -name "*.jar" -exec cp -v {} "$SCRIPT_DIR/app/libs/" \;
fi

# Cleanup
rm -rf "$TMP_DIR"

echo ""
echo "Done! Assets extracted to:"
echo "  Native libs: app/src/main/jniLibs/"
echo "  Assets:      app/src/main/assets/"
echo ""
echo "You can now build with: ./gradlew assembleRelease"
