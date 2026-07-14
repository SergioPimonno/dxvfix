#!/bin/bash
# macOS equivalent of build-app-image.ps1: packages dxvfix.jar into a self-contained
# DXVFrameDoctor.app (own bundled JRE, no Java install needed on the target Mac). Run after
# build.sh. Intended to run on macOS (jpackage cannot cross-package a Mac app from another OS) --
# see .github/workflows/build-mac.yml, which is the actual way this gets built and published.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar_path="$root/dxvfix.jar"
lib="$root/lib"
dest_dir="$root/dist"

if [ ! -f "$jar_path" ]; then
    echo "dxvfix.jar not found. Run build.sh first." >&2
    exit 1
fi
flatlaf_jar="$(find "$lib" -maxdepth 1 -name 'flatlaf-*.jar' | head -n 1)"
if [ -z "$flatlaf_jar" ]; then
    echo "FlatLaf jar not found under lib/. Run build.sh first (it checks for this too)." >&2
    exit 1
fi

if ! command -v jpackage >/dev/null 2>&1; then
    echo "jpackage not found on PATH. It ships with JDK 14+; install a full JDK (not just a JRE)." >&2
    exit 1
fi

# jpackage bundles every jar it finds in --input onto the launcher's classpath, not just
# --main-jar -- so both dxvfix.jar and its FlatLaf dependency need to sit in the same staging
# directory (same reasoning as build-app-image.ps1's Windows counterpart).
stage_dir="$root/out-appimage-stage"
rm -rf "$stage_dir"
mkdir -p "$stage_dir"
cp "$jar_path" "$stage_dir"/
cp "$flatlaf_jar" "$stage_dir"/

app_name="DXVFrameDoctor"
app_bundle="$dest_dir/$app_name.app"
rm -rf "$app_bundle"
mkdir -p "$dest_dir"

version_file="$root/src/main/java/dxvfix/AppVersion.java"
app_version="$(sed -n 's/.*VERSION = "\([0-9.]*\)".*/\1/p' "$version_file" | head -n 1)"
if [ -z "$app_version" ]; then app_version="1.0"; fi
echo "App version: $app_version (read from AppVersion.java)"

jpackage \
    --type app-image \
    --name "$app_name" \
    --input "$stage_dir" \
    --main-jar "$(basename "$jar_path")" \
    --main-class dxvfix.Main \
    --dest "$dest_dir" \
    --app-version "$app_version" \
    --vendor "SergioPimonno" \
    --java-options "-Dfile.encoding=UTF-8"

rm -rf "$stage_dir"

echo "Built self-contained app at: $app_bundle"
echo "Run: open \"$app_bundle\""
echo "To distribute: zip the '$app_name.app' bundle and copy it to another Mac -- no Java install needed there."
echo "Note: this app is not code-signed/notarized, so macOS Gatekeeper will warn on first launch;"
echo "right-click -> Open (or System Settings -> Privacy & Security -> Open Anyway) once bypasses it."
