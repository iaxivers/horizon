#!/bin/bash
# Rebuilds, starts the game with MODS, sets up a scene (SCENE: a name below), then runs each
# argument as a step, a screenshot after each in OUT (default /tmp): <scene>-N.png. Prints the
# log's failures. Steps see C (horizon-ctl). Click at GUI pixels: '$C click-at X Y'.
#   MODS=~/fd SCENE=pot   tools/java_bridge/screen_test.sh '$C click-at 141 187'
#   MODS=~/toms SCENE=terminal tools/java_bridge/screen_test.sh '$C click-at 149 45'
#   MODS=~/critters SCENE=entity tools/java_bridge/screen_test.sh '$C mouse left click'
#   MODS=~/nat SCENE=entity ENTITY=naturalist:bear tools/java_bridge/screen_test.sh
#   JAVA_ONLY=1 ... : after a Java-only change (no game rebuild; see java.sh)
R=$(cd "$(dirname "$0")/../.." && pwd)
: "${SCENE:=pot}" "${OUT:=/tmp}"
# Java 25 (26.3's classes are version 69): JAVA_HOME if it is one, else the first installed.
if ! "${JAVA_HOME:-/nonexistent}/bin/java" -version 2>&1 | grep -q '"25'; then
  JAVA_HOME=$(ls -d /usr/lib/jvm/*25* 2>/dev/null | head -1)
fi
[ -n "$JAVA_HOME" ] || { echo "no Java 25 in /usr/lib/jvm: set JAVA_HOME" >&2; exit 1; }
export JAVA_HOME HORIZON_JAVA_HOME=$JAVA_HOME PATH=$JAVA_HOME/bin:$PATH
old=$(cat /tmp/hz-bridge-scratch 2>/dev/null)
[ -n "$old" ] && "$R/target/fast/horizon-ctl" --socket "$old/ctl.sock" quit >/dev/null 2>&1 && rm -rf "$old"
# JAVA_ONLY=1: a Java-only change; just the jars are rebuilt (seconds) and run from disk.
if [ -n "$JAVA_ONLY" ]; then
  export HORIZON_JAVA_JARS=$("$R/tools/java_bridge/java.sh") || exit 1
else
  cd "$R" && cargo build -q --profile fast -p horizon-client --features horizon-client/java-mods 2>&1 \
    | grep -vE "Picked up JAVA_TOOL" | grep -E "^error|error:|\.java:[0-9]+" -A6 | head -30
fi
S=$("$R/tools/java_bridge/launch.sh") || exit 1
C="$R/target/fast/horizon-ctl --socket $S/ctl.sock"
case "$SCENE" in
  # Farmer's Delight: a lit stove, a cooking pot on it, beef and carrots; the pot opened.
  pot) "$R/tools/java_bridge/around.sh" \
    'q "setblock $((X+2)) $Y $Z farmersdelight:stove[facing=west,lit=true]"' \
    'q "setblock $((X+2)) $((Y+1)) $Z farmersdelight:cooking_pot[facing=west,support=tray]"' \
    'q "give @s minecraft:beef 3"' 'q "give @s minecraft:carrot 2"' \
    '$C look-at-block $((X+2)) $((Y+1)) $Z >/dev/null' '$C mouse right click >/dev/null' 'sleep 3' ;;
  # Tom's Storage: a chest (12 diamonds, 40 logs), a connector beside it, a terminal on the
  # connector facing it (pos=down: it reads the block below); the terminal opened.
  terminal) "$R/tools/java_bridge/around.sh" \
    'q "setblock $((X+3)) $Y $Z minecraft:chest"' \
    'q "item replace block $((X+3)) $Y $Z container.0 with minecraft:diamond 12"' \
    'q "item replace block $((X+3)) $Y $Z container.1 with minecraft:oak_log 40"' \
    'q "setblock $((X+2)) $Y $Z toms_storage:inventory_connector"' \
    'q "setblock $((X+2)) $((Y+1)) $Z toms_storage:storage_terminal[facing=west,pos=down]"' \
    '$C look-at-block $((X+2)) $((Y+1)) $Z >/dev/null' '$C mouse right click >/dev/null' 'sleep 3' ;;
  # A pack (docs/java-bridge.md, "A 20-40 mod pack"): a row of blocks from its content mods,
  # looked at from three blocks away; then a waystone used (its screen).
  pack) "$R/tools/java_bridge/around.sh" \
    'q "setblock $((X+3)) $Y $((Z-2)) biomesoplenty:redwood_log"' \
    'q "setblock $((X+3)) $Y $((Z-1)) biomesoplenty:jacaranda_leaves"' \
    'q "setblock $((X+3)) $Y $Z waystones:sandstone_waystone"' \
    'q "setblock $((X+3)) $Y $((Z+1)) farmersdelight:stove"' \
    'q "setblock $((X+3)) $Y $((Z+2)) toms_storage:inventory_connector"' \
    'q "setblock $((X+3)) $((Y+1)) $((Z-2)) biomesoplenty:blue_hydrangea"' \
    'q "setblock $((X+3)) $((Y+1)) $((Z+2)) farmersdelight:cooking_pot"' \
    '$C look-at-block $((X+3)) $Y $Z >/dev/null' 'sleep 2' ;;
  # Mods' entities: two of ENTITY (default the critter fixture: tools/java_bridge/fixture_mod.sh
  # crittermod) summoned ahead, the player facing them; one standing still (NoAI) under the crosshair.
  entity) "$R/tools/java_bridge/around.sh" \
    'q "summon ${ENTITY:-horizon_critters:critter} ~3 ~ ~2"' \
    'q "summon ${ENTITY:-horizon_critters:critter} ~2 ~ ~ {NoAI:1b}"' \
    'q "tp @s ~ ~ ~ -90 25"' 'sleep 3' ;;
  # Farmer's Delight's block entity renderers: beef on a lit stove's grill, a carrot on a
  # cutting board beside it; the player looking at both.
  ber) "$R/tools/java_bridge/around.sh" \
    'q "setblock $((X+2)) $Y $Z farmersdelight:stove[facing=west,lit=true]"' \
    'q "setblock $((X+2)) $Y $((Z+1)) farmersdelight:cutting_board[facing=west]"' \
    'q "item replace entity @s hotbar.0 with minecraft:beef 3"' \
    'q "item replace entity @s hotbar.1 with minecraft:carrot 2"' \
    'q "tp @s $(p $X+0.5) $Y $(p $Z+1.5)"' \
    '$C key 1 tap >/dev/null; $C look-at $(p $X+2.5) $((Y+1)) $(p $Z+0.5) >/dev/null' '$C mouse right click >/dev/null' \
    '$C key 2 tap >/dev/null; $C look-at $(p $X+2.5) $(p $Y+0.06) $(p $Z+1.5) >/dev/null' '$C mouse right click >/dev/null' \
    '$C look-at $(p $X+2.5) $Y $(p $Z+0.9) >/dev/null' 'sleep 1' ;;
  none) ;;
esac
n=0; for step in "$@"; do eval "$step"; sleep 1; n=$((n+1)); $C screenshot "$S/s$n.png" >/dev/null; cp "$S/s$n.png" "$OUT/$SCENE-$n.png"; done
$C screenshot "$S/s0.png" >/dev/null; cp "$S/s0.png" "$OUT/$SCENE-0.png"
grep -E "failed|could not|    at " "$S/home/logs/latest.log" | grep -v "content:" | head -30 | cut -c1-260
echo "screenshots: $OUT/$SCENE-*.png  log: $S/home/logs/latest.log"
