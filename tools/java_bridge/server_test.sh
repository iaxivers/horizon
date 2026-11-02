#!/bin/bash
# A dedicated server with the Java mods of MODS (horizon-client --dedicated, mods-java/ beside it)
# and a game that joins it as a player (made operator), each in its own scratch folder. Then runs
# each argument as a step, a screenshot of the player's game after each in OUT (default /tmp):
# server-N.png. Steps see G (horizon-ctl of the player's game), sv (a server console command)
# and X Y Z (the player's block). Prints the logs' failures.
#   MODS=~/mp tools/java_bridge/server_test.sh '$G key e tap; sleep 6'
#   JAVA_ONLY=1 ... : after a Java-only change (no game rebuild; see java.sh)
R=$(cd "$(dirname "$0")/../.." && pwd)
: "${MODS:?MODS: a folder of mod jars}" "${OUT:=/tmp}" "${PORT:=25611}"
if ! "${JAVA_HOME:-/nonexistent}/bin/java" -version 2>&1 | grep -q '"25'; then
  JAVA_HOME=$(ls -d /usr/lib/jvm/*25* 2>/dev/null | head -1)
fi
export JAVA_HOME HORIZON_JAVA_HOME=$JAVA_HOME PATH=$JAVA_HOME/bin:$PATH
if [ -n "$JAVA_ONLY" ]; then
  export HORIZON_JAVA_JARS=$("$R/tools/java_bridge/java.sh") || exit 1
else
  cd "$R" && cargo build -q --profile fast -p horizon-client -p horizon-client-lib --features horizon-client/java-mods 2>&1 \
    | grep -vE "Picked up JAVA_TOOL" | grep -E "^error|error:|\.java:[0-9]+" -A6 | head -30
fi
CTL="$R/target/fast/horizon-ctl"; mkdir -p "$OUT"
# The last run's server and player game go.
for f in /tmp/hz-bridge-server /tmp/hz-bridge-player; do
  old=$(cat "$f" 2>/dev/null || true)
  [ -n "$old" ] && [ -d "$old" ] || continue
  [ -S "$old/ctl.sock" ] && "$CTL" --socket "$old/ctl.sock" quit >/dev/null 2>&1
  [ -f "$old/pid" ] && kill "$(cat "$old/pid")" 2>/dev/null
  for _ in $(seq 40); do pgrep -f "$old" >/dev/null || break; sleep 0.5; done
  rm -rf "$old" 2>/dev/null || true
done
[ -z "$DISPLAY" ] && { pgrep -x Xvfb >/dev/null || (nohup Xvfb :77 -screen 0 1280x1024x24 >/tmp/xvfb.log 2>&1 &); export DISPLAY=:77; sleep 1; }
scratch() {
  local d; d=$(mktemp -d "/tmp/hz-$1.XXXX")
  mkdir -p "$d/mods-java" "$d/home" "$d/lh" "$d/data" "$d/conf"
  cp "$MODS"/*.jar "$d/mods-java/"
  echo "$d"
}
SD=$(scratch server); echo "$SD" > /tmp/hz-bridge-server
printf 'server-port=%s\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nlevel-seed=%s\ngamemode=survival\nspawn-protection=0\nmotd=Horizon bridge test\n' \
  "$PORT" "${SEED:-1234}" > "$SD/server.properties"
echo 'eula=true' > "$SD/eula.txt"
mkfifo "$SD/console"
(cd "$SD" && HORIZON_HOME=$SD/home HORIZON_LAUNCHER_HOME=$SD/lh XDG_DATA_HOME=$SD/data XDG_CONFIG_HOME=$SD/conf \
  HORIZON_LAUNCHER_NO_SELF_UPDATE=1 HORIZON_NO_AUDIO=1 HORIZON_ROOT=$R \
  exec "$R/target/fast/horizon-client" --resources "$R/.local/horizon-client/26.3" --dedicated --nogui \
  < "$SD/console" > "$SD/server.log" 2>&1) &
echo $! > "$SD/pid"
# The server goes with this script.
trap 'kill "$(cat "$SD/pid")" 2>/dev/null' EXIT
exec 3>"$SD/console"
sv() { echo "$1" >&3; sleep 0.5; }
for _ in $(seq 600); do grep -q 'Done (' "$SD/server.log" 2>/dev/null && break; sleep 1; done
grep -q 'Done (' "$SD/server.log" || { echo "the server never started: $SD/server.log"; tail -20 "$SD/server.log"; exit 1; }
GS=$(scratch player); echo "$GS" > /tmp/hz-bridge-player
(cd "$GS" && HORIZON_HOME=$GS/home HORIZON_LAUNCHER_HOME=$GS/lh XDG_DATA_HOME=$GS/data XDG_CONFIG_HOME=$GS/conf \
  HORIZON_LAUNCHER_NO_SELF_UPDATE=1 HORIZON_NO_AUDIO=1 HORIZON_ROOT=$R \
  "$CTL" --socket "$GS/ctl.sock" launch --offscreen \
  --game "$R/target/fast/horizon-client" --resources "$R/.local/horizon-client/26.3" > "$GS/ctl.out" 2>&1)
G="$CTL --socket $GS/ctl.sock"
$G connect "localhost:$PORT" Player >/dev/null
$G wait-ready >/dev/null 2>&1
sv "op Player"
sleep 3
read X Y Z < <($G state | python3 -c "import json,sys,math;p=json.load(sys.stdin)['player']['position'];print(math.floor(p[0]),math.floor(p[1]),math.floor(p[2]))")
n=0
for step in "$@"; do
  eval "$step"; sleep 1; n=$((n+1))
  $G screenshot "$GS/s$n.png" >/dev/null; cp "$GS/s$n.png" "$OUT/server-$n.png"
done
for log in "$SD/server.log" "$GS/home/logs/latest.log"; do
  echo "== $log"
  grep -E "failed|could not|    at " "$log" | grep -v "content:" | head -15 | cut -c1-240
done
echo "server: $SD  player: $GS  screenshots: $OUT/server-*.png"
