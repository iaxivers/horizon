#!/bin/bash
# Two games: a host with the Java mods of MODS (launch.sh), opened to LAN, and a guest that
# joins it (its own scratch folder, the same jars for their textures). Then runs each argument
# as a step, a screenshot of both after each in OUT (default /tmp): lan-N-host.png,
# lan-N-guest.png. Steps see H and G (horizon-ctl of the host and the guest), and X Y Z (the
# host player's block). Prints the logs' failures.
#   MODS=~/fd tools/java_bridge/lan_test.sh '$G command "give @s minecraft:beef"' '$G key e tap'
#   JAVA_ONLY=1 ... : after a Java-only change (no game rebuild; see java.sh)
R=$(cd "$(dirname "$0")/../.." && pwd)
: "${MODS:?MODS: a folder of mod jars}" "${OUT:=/tmp}" "${PORT:=25599}"
if ! "${JAVA_HOME:-/nonexistent}/bin/java" -version 2>&1 | grep -q '"25'; then
  JAVA_HOME=$(ls -d /usr/lib/jvm/*25* 2>/dev/null | head -1)
fi
export JAVA_HOME HORIZON_JAVA_HOME=$JAVA_HOME PATH=$JAVA_HOME/bin:$PATH
if [ -n "$NO_BUILD" ]; then
  :  # the caller built already (regress.py)
elif [ -n "$JAVA_ONLY" ]; then
  export HORIZON_JAVA_JARS=$("$R/tools/java_bridge/java.sh") || exit 1
else
  cd "$R" && cargo build -q --profile fast -p horizon-client -p horizon-client-lib --features horizon-client/java-mods 2>&1 \
    | grep -vE "Picked up JAVA_TOOL" | grep -E "^error|error:|\.java:[0-9]+" -A6 | head -30
fi
CTL="$R/target/fast/horizon-ctl"
# The guest of the last run goes.
oldg=$(cat /tmp/hz-bridge-guest 2>/dev/null)
if [ -n "$oldg" ] && [ -d "$oldg" ]; then
  "$CTL" --socket "$oldg/ctl.sock" quit >/dev/null 2>&1 || true
  rm -rf "$oldg" 2>/dev/null || { sleep 5; rm -rf "$oldg"; }
fi
S=$("$R/tools/java_bridge/launch.sh") || exit 1
H="$CTL --socket $S/ctl.sock"
$H lan open "$PORT" commands >/dev/null
# The guest: a fresh scratch folder, to the title screen, then joins.
GS=$(mktemp -d /tmp/hz-guest.XXXX)
echo "$GS" > /tmp/hz-bridge-guest
mkdir -p "$GS/mods-java" "$GS/home" "$GS/lh" "$GS/data" "$GS/conf"
cp "$MODS"/*.jar "$GS/mods-java/"
[ -z "$DISPLAY" ] && export DISPLAY=:77
(cd "$GS" && HORIZON_HOME=$GS/home HORIZON_LAUNCHER_HOME=$GS/lh XDG_DATA_HOME=$GS/data XDG_CONFIG_HOME=$GS/conf \
  HORIZON_LAUNCHER_NO_SELF_UPDATE=1 HORIZON_NO_AUDIO=1 HORIZON_ROOT=$R \
  "$CTL" --socket "$GS/ctl.sock" launch --offscreen \
  --game "$R/target/fast/horizon-client" --resources "$R/.local/horizon-client/26.3" > "$GS/ctl.out" 2>&1)
G="$CTL --socket $GS/ctl.sock"
$G connect "localhost:$PORT" Guest >/dev/null
$G wait-ready >/dev/null 2>&1
sleep 3
read X Y Z < <($H state | python3 -c "import json,sys,math;p=json.load(sys.stdin)['player']['position'];print(math.floor(p[0]),math.floor(p[1]),math.floor(p[2]))")
n=0
for step in "$@"; do
  eval "$step"; sleep 1; n=$((n+1))
  $H screenshot "$S/h$n.png" >/dev/null; cp "$S/h$n.png" "$OUT/lan-$n-host.png"
  $G screenshot "$GS/g$n.png" >/dev/null; cp "$GS/g$n.png" "$OUT/lan-$n-guest.png"
done
for log in "$S/home/logs/latest.log" "$GS/home/logs/latest.log"; do
  echo "== $log"
  grep -E "failed|could not|    at " "$log" | grep -v "content:" | head -15 | cut -c1-240
done
echo "host: $S  guest: $GS  screenshots: $OUT/lan-*.png"
