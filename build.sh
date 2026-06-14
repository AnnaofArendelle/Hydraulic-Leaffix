#!/usr/bin/env bash
#
# Reproducible build for hydraulic-leaffix.
#
# Compile dependencies are taken straight from the running server so they match the
# runtime versions exactly. Run this from inside the server tree (this project folder
# normally lives in the server root), or point SERVER_ROOT at the server directory.
#
#   ./build.sh
#   SERVER_ROOT=/path/to/server ./build.sh
#
set -euo pipefail

VERSION="1.3.1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_ROOT="${SERVER_ROOT:-$(dirname "$SCRIPT_DIR")}"

echo ">> Server root: $SERVER_ROOT"

# --- locate compile-only dependencies from the server ------------------------------------
MIXIN_JAR="$(find "$SERVER_ROOT/libraries" -name 'sponge-mixin-*.jar' 2>/dev/null | head -1)"
MEXTRAS_JAR="$(find "$SERVER_ROOT/.fabric" -name 'mixinextras-*.jar' 2>/dev/null | head -1)"
ASM_JARS="$(find "$SERVER_ROOT/libraries/org/ow2/asm" -name '*.jar' 2>/dev/null | tr '\n' ':')"
BEDFRAME_JAR="$(find "$SERVER_ROOT/mods" -name 'bedframe-*.jar' 2>/dev/null | head -1)"
HYDRAULIC_JAR="$(find "$SERVER_ROOT/mods" -name 'hydraulic-fabric*.jar' 2>/dev/null | head -1)"
# Minecraft (intermediary-mapped) + Geyser + packet_tweaker are needed for the Bedrock item-component fix and
# the entity-translator neutraliser, which reference net.minecraft.class_*, GeyserApi and PacketContext.
MINECRAFT_JAR="$(find "$SERVER_ROOT/.fabric/remappedJars" -name 'server-intermediary.jar' 2>/dev/null | head -1)"
GEYSER_JAR="$(find "$SERVER_ROOT/mods" -iname 'Geyser*.jar' -o -iname 'geyser-fabric*.jar' 2>/dev/null | head -1)"
PKTTWEAKER_JAR="$(find "$SERVER_ROOT/.fabric" "$SERVER_ROOT/mods" -iname 'packet_tweaker-*.jar' 2>/dev/null | head -1)"
# The Minecraft API signatures we touch (DataComponentType/Registry/Codec/...) pull in transitive library
# types (datafixerupper, gson, guava, fastutil, jspecify, ...), which javac must resolve to complete symbols.
# Put every server library jar on the compile-only classpath so symbol completion always succeeds.
LIB_DEPS="$(find "$SERVER_ROOT/libraries" -name '*.jar' 2>/dev/null | tr '\n' ':')"
# Fabric-processed mod jars (intermediary-mapped) carry the Geyser/Floodgate API split modules
# (org.geysermc.event, org.geysermc.api base) and packet_tweaker that the Geyser API symbols pull in.
FABRIC_MOD_DEPS="$(find "$SERVER_ROOT/.fabric/processedMods" -name '*.jar' 2>/dev/null | tr '\n' ':')"

# Guava's class files carry TYPE_USE annotations from checker-qual; javac CRASHES (not just warns) if that
# annotation jar is absent while it completes a guava symbol. It is not shipped with the server, so fetch it
# into a local cache (once) when missing. Network is only needed the first time.
CACHE_DIR="$SCRIPT_DIR/.build-cache"
mkdir -p "$CACHE_DIR"
CHECKER_QUAL="$(find "$SERVER_ROOT/libraries" "$CACHE_DIR" -name 'checker-qual-*.jar' 2>/dev/null | head -1 || true)"
if [ -z "$CHECKER_QUAL" ]; then
  CHECKER_QUAL="$CACHE_DIR/checker-qual-3.49.0.jar"
  echo ">> Fetching checker-qual (compile-only annotation dep not present in the server)..."
  curl -fsSL -o "$CHECKER_QUAL" \
    "https://repo1.maven.org/maven2/org/checkerframework/checker-qual/3.49.0/checker-qual-3.49.0.jar" \
    || { echo "!! Could not download checker-qual. Place any checker-qual-*.jar in $CACHE_DIR and re-run." >&2; exit 1; }
fi
echo ">> checker-qual  : $CHECKER_QUAL"

for v in MIXIN_JAR MEXTRAS_JAR BEDFRAME_JAR HYDRAULIC_JAR MINECRAFT_JAR GEYSER_JAR PKTTWEAKER_JAR; do
  if [ -z "${!v}" ]; then
    echo "!! Could not find $v under $SERVER_ROOT. Set SERVER_ROOT to your server directory." >&2
    exit 1
  fi
done
echo ">> sponge-mixin  : $MIXIN_JAR"
echo ">> mixinextras   : $MEXTRAS_JAR"
echo ">> bedframe      : $BEDFRAME_JAR"
echo ">> hydraulic     : $HYDRAULIC_JAR"
echo ">> minecraft     : $MINECRAFT_JAR"
echo ">> geyser        : $GEYSER_JAR"
echo ">> packet_tweaker: $PKTTWEAKER_JAR"

# --- extract the pack-converter (and its sibling libs) bundled in Bedframe ----------------
# Bedframe carries the exact converter build that is loaded at runtime (contains
# GridSpritesheetParticleTransformer), so we compile against the same classes.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -o -q "$BEDFRAME_JAR" 'META-INF/jars/*.jar' -d "$WORK"
CONV_LIBS="$(find "$WORK/META-INF/jars" -name '*.jar' | tr '\n' ':')"

# hydraulic-fabric.jar is needed compile-only so the @WrapOperation handler can declare its
# receiver as the real org.geysermc.hydraulic.block.Materials$Material type (MixinExtras requires
# the exact type, not Object). Provided at runtime by the Hydraulic mod.
CP="$MIXIN_JAR:$MEXTRAS_JAR:$ASM_JARS$CONV_LIBS:$HYDRAULIC_JAR:$BEDFRAME_JAR:$MINECRAFT_JAR:$GEYSER_JAR:$PKTTWEAKER_JAR:$LIB_DEPS:$FABRIC_MOD_DEPS:$CHECKER_QUAL"

# --- compile -----------------------------------------------------------------------------
OUT="$SCRIPT_DIR/build/classes"
rm -rf "$SCRIPT_DIR/build"
mkdir -p "$OUT"
echo ">> Compiling..."
javac --release 21 -cp "$CP" -d "$OUT" \
  "$SCRIPT_DIR"/src/main/java/dev/fwq/hydleaffix/mixin/*.java

# --- package -----------------------------------------------------------------------------
cp "$SCRIPT_DIR"/src/main/resources/*.json "$OUT"/
OUT_JAR="$SCRIPT_DIR/hydraulic-leaffix-${VERSION}.jar"
( cd "$OUT" && jar --create --file "$OUT_JAR" . )

echo ">> Built: $OUT_JAR"
unzip -l "$OUT_JAR"
