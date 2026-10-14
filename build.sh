#!/usr/bin/env bash
# Tint Guard - offline build (plain javac, no Gradle, no network).
#
# Prefer `./gradlew build` if you have network access. This script exists for
# machines that cannot reach Maven, and for quick local rebuilds.
#
# It needs NeoForge + Mixin + slf4j jars from a local Minecraft installation,
# so point the variables below at yours:
#
#   MC_DIR   game directory whose libraries/ holds the jars
#            (e.g. ~/.minecraft)
#   JDK      a JDK 21 home
#
# Usage:
#   MC_DIR=/path/to/.minecraft JDK=/path/to/jdk-21 bash build.sh
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JDK:-/c/Users/26703/.jdks/jdk-21.0.12.1+1}"
MC_DIR="${MC_DIR:-/c/Users/26703/Desktop/233/.minecraft}"
LIB="$MC_DIR/libraries"

# Keep in sync with build.gradle.
NEOFORGE_VERSION="${NEOFORGE_VERSION:-21.1.249}"
LOADER_VERSION="${LOADER_VERSION:-4.0.44}"

MC_CLIENT="$LIB/net/neoforged/neoforge/$NEOFORGE_VERSION/neoforge-$NEOFORGE_VERSION-client.jar"
MIXIN="$LIB/net/fabricmc/sponge-mixin/0.16.4+mixin.0.8.7/sponge-mixin-0.16.4+mixin.0.8.7.jar"
LOADER="$LIB/net/neoforged/fancymodloader/loader/$LOADER_VERSION/loader-$LOADER_VERSION.jar"
SLF4J="$LIB/org/slf4j/slf4j-api/2.0.1/slf4j-api-2.0.1.jar"

JAVAC="$JDK/bin/javac.exe"
JAR_TOOL="$JDK/bin/jar.exe"
[ -x "$JAVAC" ] || { echo "javac not found at $JAVAC (set JDK=...)"; exit 1; }

W() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

rm -rf out stubout build
mkdir -p out stubout

echo "=== 1/4 compiling stubs (offline-only shims, never packaged) ==="
"$JAVAC" -nowarn -proc:none -encoding UTF-8 -d "$(W stubout)" \
  $(find stubs -name '*.java')

echo "=== 2/4 compiling the mod against the stubs ==="
CP="$(W stubout);$(W "$MC_CLIENT");$(W "$MIXIN");$(W "$LOADER");$(W "$SLF4J")"
"$JAVAC" -J-Duser.language=en -J-Duser.country=US \
  --release 21 -proc:none -nowarn -encoding UTF-8 \
  -cp "$CP" -d out \
  src/main/java/com/kokuustudio/tintguard/TintGuard.java \
  src/main/java/com/kokuustudio/tintguard/SafeBlockColor.java \
  src/main/java/com/kokuustudio/tintguard/mixin/BlockColorsMixin.java

echo "=== 3/4 staging (com/ + resources only, stubs excluded) ==="
mkdir -p build/stage
cp -r out/com build/stage/
cp -r src/main/resources/. build/stage/
cat > build/manifest.mf <<'EOF'
Manifest-Version: 1.0
MixinConfigs: tintguard.mixins.json
EOF

echo "=== 4/4 packaging ==="
VERSION="$(grep -oE "version = '[^']+'" build.gradle | head -1 | sed "s/version = '//; s/'//")"
VERSION="${VERSION:-1.0.0}"
OUT="build/libs/tintguard-$VERSION.jar"
mkdir -p build/libs
"$JAR_TOOL" --create --file "$OUT" --manifest build/manifest.mf -C build/stage .
echo "built: $OUT"
"$JAR_TOOL" --list --file "$OUT"

echo "sanity: no stub classes may leak into the jar"
if "$JAR_TOOL" --list --file "$OUT" | grep -q '^net/minecraft'; then
  echo "FAIL: stubs leaked into the jar"
  exit 1
fi
echo "OK"
