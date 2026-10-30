#!/bin/bash
# Starts the game headless with the Java mods of MODS (a folder of jars), in a fresh scratch
# folder (CLAUDE.md: never real player data), driven by horizon-ctl. Prints the scratch folder;
# its ctl.sock is the socket. The world has seed SEED (default 1234: the same land each run).
# Usage: MODS=~/mods tools/java_bridge/launch.sh [game args...]
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
: "${MODS:?MODS: a folder of mod jars}"
# Java 25 (26.3's classes are version 69): JAVA_HOME if it is one, else the first installed.
if ! "${JAVA_HOME:-/nonexistent}/bin/java" -version 2>&1 | grep -q '"25'; then
  JAVA_HOME=$(ls -d /usr/lib/jvm/*25* 2>/dev/null | head -1)
fi
[ -n "$JAVA_HOME" ] || { echo "no Java 25 in /usr/lib/jvm: set JAVA_HOME" >&2; exit 1; }
export JAVA_HOME HORIZON_JAVA_HOME=$JAVA_HOME PATH=$JAVA_HOME/bin:$PATH
S=$(mktemp -d /tmp/hz-bridge.XXXX)
mkdir -p "$S/mods-java" "$S/home" "$S/lh" "$S/data" "$S/conf"
cp "$MODS"/*.jar "$S/mods-java/"
[ -n "$OPTS" ] && cp "$OPTS" "$S/options.txt"
echo "$S" > /tmp/hz-bridge-scratch
# A display for the offscreen renderer when there is none.
if [ -z "$DISPLAY" ]; then
  pgrep -x Xvfb >/dev/null || (nohup Xvfb :77 -screen 0 1280x1024x24 >/tmp/xvfb.log 2>&1 &)
  export DISPLAY=:77; sleep 1
fi
cd "$S"
export HORIZON_HOME=$S/home HORIZON_LAUNCHER_HOME=$S/lh XDG_DATA_HOME=$S/data XDG_CONFIG_HOME=$S/conf \
  HORIZON_LAUNCHER_NO_SELF_UPDATE=1 HORIZON_NO_AUDIO=1 HORIZON_ROOT=$R
"$R/target/fast/horizon-ctl" --socket "$S/ctl.sock" launch --world W --seed "${SEED:-1234}" --creative --offscreen --wait \
  --game "$R/target/fast/horizon-client" --resources "$R/.local/horizon-client/26.3" "$@" > "$S/ctl.out" 2>&1
echo "$S"
