#!/bin/bash
# Runs steps around the player of the running game (launch.sh's): a 9x9 stone floor and air
# above, then each step, a second apart. Steps are shell, with C (horizon-ctl), q (a command,
# its chat printed) and X Y Z (the player's block) defined.
# Usage: tools/java_bridge/around.sh 'q "setblock $((X+2)) $Y $Z minecraft:chest"' ...
R=$(cd "$(dirname "$0")/../.." && pwd)
S=$(cat /tmp/hz-bridge-scratch); C="$R/target/fast/horizon-ctl --socket $S/ctl.sock"
q(){ $C command "$1" | python3 -c "import json,sys;print(' ', json.load(sys.stdin).get('chat'))"; }
read X Y Z < <($C state | python3 -c "import json,sys,math;p=json.load(sys.stdin)['player']['position'];print(math.floor(p[0]),math.floor(p[1]),math.floor(p[2]))")
echo "player at $X $Y $Z"
q "fill $((X-4)) $((Y-1)) $((Z-4)) $((X+4)) $((Y-1)) $((Z+4)) stone"
q "fill $((X-4)) $Y $((Z-4)) $((X+4)) $((Y+3)) $((Z+4)) air"
for step in "$@"; do eval "$step"; sleep 1; done
