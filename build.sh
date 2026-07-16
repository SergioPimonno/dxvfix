#!/bin/bash
# Shell equivalent of build.ps1, for macOS/Linux (used by the GitHub Actions macOS build).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
src="$root/src/main/java"
resources="$root/src/main/resources"
out="$root/out"
lib="$root/lib"

flatlaf_jar="$(find "$lib" -maxdepth 1 -name 'flatlaf-*.jar' | head -n 1)"
if [ -z "$flatlaf_jar" ]; then
    echo "FlatLaf jar not found under lib/. Expected lib/flatlaf-<version>.jar (theming depends on it)." >&2
    exit 1
fi

rm -rf "$out"
mkdir -p "$out"

echo "Compiling sources..."
# NOT `mapfile` -- it's a Bash 4+ builtin, but macOS's system /bin/bash is frozen at 3.2 (Apple
# stopped updating it in 2007 over GPLv3 licensing), which is exactly what GitHub's macos-latest
# runners invoke for a plain `#!/bin/bash` shebang. This `while read` + array-append idiom works
# on both.
sources=()
while IFS= read -r f; do
    sources+=("$f")
done < <(find "$src" -name '*.java')
# --release 17 for the same reason as build.ps1/build-keygen.ps1: pins the output bytecode to
# JRE 17+ regardless of which JDK actually runs this script (matters most for CI, where the
# runner's JDK version is whatever actions/setup-java was told to install).
javac --release 17 -encoding UTF-8 -cp "$flatlaf_jar" -d "$out" "${sources[@]}"

if [ -d "$resources" ]; then
    echo "Copying resources..."
    cp -R "$resources"/. "$out"/
fi
if [ ! -f "$out/dxvfix/license/public.key" ]; then
    echo "WARNING: src/main/resources/dxvfix/license/public.key is missing - license verification will fail at runtime." >&2
fi

manifest="$out/MANIFEST.MF"
printf 'Main-Class: dxvfix.Main\r\nClass-Path: lib/%s\r\n' "$(basename "$flatlaf_jar")" > "$manifest"

jar_path="$root/dxvfix.jar"
(cd "$out" && jar cfm "$jar_path" "MANIFEST.MF" .)

echo "Built $jar_path"
echo "Run with: java -jar dxvfix.jar (lib/$(basename "$flatlaf_jar") must stay alongside it)"
