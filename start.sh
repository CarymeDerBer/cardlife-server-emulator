#!/usr/bin/env bash
# Linux/macOS: server-only mode and the GUI (the game itself is a Windows application).
set -euo pipefail
cd "$(dirname "$0")"

find_java() {
  for c in "${JAVA_HOME:-}" /usr/lib/jvm/*17* /usr/lib/jvm/*21* /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    [ -n "$c" ] && [ -x "$c/bin/javac" ] && { echo "$c"; return 0; }
  done
  if command -v javac >/dev/null 2>&1; then dirname "$(dirname "$(readlink -f "$(command -v javac)")")"; return 0; fi
  return 1
}

JAVA_HOME="$(find_java)" || { echo "[ERROR] JDK 17+ not found. Install it (e.g. sudo apt install openjdk-17-jdk) and re-run."; exit 1; }
export JAVA_HOME
echo "Using JDK: $JAVA_HOME"

FLAT=3.6.1
mkdir -p lib
if [ ! -s "lib/flatlaf-$FLAT.jar" ]; then
  for base in https://repo1.maven.org/maven2 https://repo.maven.apache.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do
    url="$base/com/formdev/flatlaf/$FLAT/flatlaf-$FLAT.jar"
    echo "Downloading $url"
    if curl -fL --retry 3 --connect-timeout 20 -o "lib/flatlaf-$FLAT.jar.part" "$url" && unzip -tq "lib/flatlaf-$FLAT.jar.part" >/dev/null; then
      mv "lib/flatlaf-$FLAT.jar.part" "lib/flatlaf-$FLAT.jar"; break
    fi
    rm -f "lib/flatlaf-$FLAT.jar.part"
  done
  [ -s "lib/flatlaf-$FLAT.jar" ] || echo "[WARN] FlatLaf not downloaded - standard look and feel will be used."
fi

rm -rf build && mkdir -p build/classes
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 17 -cp "lib/*" -d build/classes ./*.java
printf 'Main-Class: cardlife.Main\nClass-Path: lib/flatlaf-%s.jar\n' "$FLAT" > build/manifest.mf
"$JAVA_HOME/bin/jar" --create --file cardlife-launcher.jar --manifest build/manifest.mf -C build/classes .

if [ "${1:-}" = "--cli" ]; then shift; exec "$JAVA_HOME/bin/java" -jar cardlife-launcher.jar "$@"; fi
exec "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -jar cardlife-launcher.jar --launcher
