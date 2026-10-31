#!/usr/bin/env bash
# Plays a Prism Launcher instance's Fabric mods in Horizon, through the Java bridge.
#
# Set up once (docs/java-bridge.md, "Play a Prism instance"):
#   1. In Prism, make a Minecraft 26.3 instance with Fabric Loader, and install mods or a
#      modpack in it as usual (Modrinth, CurseForge, or jars in its mods folder).
#   2. Edit instance > Settings > Custom commands > Wrapper command:
#        /path/to/horizon/tools/java_bridge/prism.sh
#   3. Press Play. Prism starts this script in place of Java; this starts Horizon instead.
#
# What it does: the instance's game folder gets a `horizon/` folder (Horizon's options and
# worlds, so each instance has its own), whose `mods-java/` holds links to the instance's mod
# jars, Fabric API left out (the bridge brings its own, made for it). Prism's signed-in account
# goes to the game in a private file it reads once and deletes. Then the game starts from this
# checkout: built with `cargo build --profile fast -p horizon-client --features
# horizon-client/java-mods`, with the Minecraft files prepared in `.local/` (CONTRIBUTING.md).
#
# HORIZON_PRISM_ARGS: more arguments for the game (e.g. "--world Test --creative").
# HORIZON_PRISM_DRY=1: set everything up, print the command, don't start the game.
set -euo pipefail

ROOT=$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)
GAME="${INST_MC_DIR:-$PWD}"
say() { printf 'Horizon (Prism): %s\n' "$*" >&2; }

# Prism runs its own Java entry point through the wrapper and hands it the game's parameters
# on stdin ("param <value>" lines, then "launch"): the account is read from them (and from the
# arguments, as plain launchers pass them).
args=("$@")
if [ ! -t 0 ]; then
    while IFS= read -r line; do
        case "$line" in
            "param "*) args+=("${line#param }") ;;
            launch | abort) break ;;
        esac
    done
fi
NAME="" UUID="" TOKEN="" XUID=""
for ((i = 0; i + 1 < ${#args[@]}; i++)); do
    case "${args[$i]}" in
        --username) NAME="${args[$((i + 1))]}" ;;
        --uuid) UUID="${args[$((i + 1))]}" ;;
        --accessToken) TOKEN="${args[$((i + 1))]}" ;;
        --xuid) XUID="${args[$((i + 1))]}" ;;
    esac
done
unset args

# This checkout's game and its prepared files.
CLIENT="$ROOT/target/fast/horizon-client"
RESOURCES="$ROOT/.local/horizon-client/26.3"
[ -x "$CLIENT" ] || { say "no game built at $CLIENT: run cargo build --profile fast -p horizon-client --features horizon-client/java-mods in $ROOT"; exit 1; }
[ -d "$RESOURCES" ] || { say "no prepared Minecraft files at $RESOURCES: see $ROOT/CONTRIBUTING.md"; exit 1; }

# Java 25 for the mods: Prism's own Java for the instance if it is 25 or newer, else the
# bridge looks for one itself (JAVA_HOME, the PATH, Minecraft's runtimes).
if [ -z "${HORIZON_JAVA_HOME:-}" ] && [ -n "${INST_JAVA:-}" ] && [ -x "$INST_JAVA" ]; then
    major=$("$INST_JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
    if [ "${major:-0}" -ge 25 ]; then
        export HORIZON_JAVA_HOME
        HORIZON_JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$INST_JAVA")")")
    fi
fi

# The instance's Horizon folder, and its mods as the bridge's.
HOME_DIR="$GAME/horizon"
MODS="$HOME_DIR/mods-java"
mkdir -p "$MODS"
find "$MODS" -maxdepth 1 -type l -name '*.jar' -delete
count=0
shopt -s nullglob
for jar in "$GAME"/mods/*.jar; do
    # Fabric API is the bridge's own (two copies would clash).
    if unzip -p "$jar" fabric.mod.json 2>/dev/null | tr -d ' \n\t' | grep -q '"id":"fabric-api"'; then
        say "leaving out $(basename "$jar") (Horizon brings its own Fabric API)"
        continue
    fi
    ln -s "$(readlink -f "$jar")" "$MODS/$(basename "$jar")"
    count=$((count + 1))
done
say "$count mods from $GAME/mods"
# The mods' settings are the instance's own (a modpack ships its config/): the folders the
# mods write beside Horizon's options are links to the instance's.
for d in config defaultconfigs; do
    mkdir -p "$GAME/$d"
    if [ ! -e "$HOME_DIR/$d" ] || [ -L "$HOME_DIR/$d" ]; then
        ln -sfn "$GAME/$d" "$HOME_DIR/$d"
    else
        say "$HOME_DIR/$d is a folder of its own, not the instance's $d/: left as it is"
    fi
done

# The account Prism signed in with: a file only this user can read, read once by the game.
if [ -n "$TOKEN" ] && [ "$TOKEN" != "0" ] && [ -n "$UUID" ]; then
    SESSION="$HOME_DIR/session.json"
    (umask 077 && python3 -I -c 'import json, sys; print(json.dumps({"name": sys.argv[1], "uuid": sys.argv[2], "access_token": sys.argv[3], "user_type": "msa", **({"xuid": sys.argv[4]} if sys.argv[4] else {})}))' \
        "$NAME" "$UUID" "$TOKEN" "$XUID" > "$SESSION")
    export HORIZON_SESSION_FILE="$SESSION"
    say "signed in as $NAME"
else
    say "no Microsoft account from Prism: playing offline${NAME:+ as $NAME}"
fi
unset TOKEN

export HORIZON_HOME="$HOME_DIR" HORIZON_ROOT="$ROOT"
cd "$HOME_DIR"
# shellcheck disable=SC2086 # HORIZON_PRISM_ARGS is meant to split into arguments.
cmd=("$CLIENT" --resources "$RESOURCES" ${NAME:+--name "$NAME"} ${HORIZON_PRISM_ARGS:-})
if [ -n "${HORIZON_PRISM_DRY:-}" ]; then
    rm -f "$HOME_DIR/session.json"
    printf '%q ' "${cmd[@]}"
    echo
    exit 0
fi
exec "${cmd[@]}"
