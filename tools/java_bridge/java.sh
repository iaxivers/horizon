#!/bin/bash
# Rebuilds only the bridge's Java (horizon-bridge.jar, horizon-platform.jar) into
# .local/bridge/dev, in seconds: no game rebuild. A game started with HORIZON_JAVA_JARS set to
# that folder runs them (screen_test.sh with JAVA_ONLY=1 does). Rust changes still need the
# full build.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
if ! "${JAVA_HOME:-/nonexistent}/bin/java" -version 2>&1 | grep -q '"25'; then
  JAVA_HOME=$(ls -d /usr/lib/jvm/*25* 2>/dev/null | head -1)
fi
export JAVA_HOME HORIZON_JAVA_HOME=$JAVA_HOME PATH=$JAVA_HOME/bin:$PATH
cd "$R"
# A failed build must not leave the last jars in place looking current.
if ! out=$(cargo build -q --profile fast -p horizon-jvm 2>&1); then
  echo "$out" | grep -vE "Picked up JAVA_TOOL" | grep -E "^error|error:|\.java:[0-9]+" -A6 | head -30 >&2
  exit 1
fi
dev="$R/.local/bridge/dev"; mkdir -p "$dev"
for jar in horizon-bridge.jar horizon-platform.jar; do
  newest=$(ls -t "$R"/target/fast/build/horizon-jvm-*/out/$jar 2>/dev/null | head -1)
  [ -n "$newest" ] || { echo "no $jar built" >&2; exit 1; }
  cp "$newest" "$dev/$jar"
done
echo "$dev"
