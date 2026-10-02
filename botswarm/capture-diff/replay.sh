#!/usr/bin/env bash
# Replay capture scenarios on a local dev server, diff each against live, and aggregate.
#
#   tools/capture-diff/replay.sh relic-unlock-t8
#   tools/capture-diff/replay.sh --manifest relic-confirm          # every extracted occurrence
#   tools/capture-diff/replay.sh --build relic-unlock-t8 relic-unlock-t8-two-players
#   tools/capture-diff/replay.sh --diff-only --manifest relic-confirm   # re-diff, no server
#   tools/capture-diff/replay.sh --repeat 2 relic-unlock-t8   # run twice; drop what is flaky
#   tools/capture-diff/replay.sh --parallel 5 --manifest prayer-toggle   # 5 bots at once
#   tools/capture-diff/replay.sh --diff-only --accept --manifest spec-orb  # accept as baseline
#
# Parallel replays share one world like real players: fine for per-player flows, but a flow with
# cross-player state (e.g. the relic menu's shared "viewed relic" bug) interferes with itself. Use
# --parallel 1 for those, or --repeat 2 so the interference shows up as flaky.
#
# Run from the repo root of a worktree whose game.yml is ISOLATED (spare game-port, central
# link-port/http-port, empty jdbc-url => embedded Postgres) and, for Leagues flows, has
# `league: true` + `league-type: 6`. See docs/bot-swarm.md "Capture diff". The script refuses to
# boot on the shared default port 43594.
#
# Per scenario it writes .data/botswarm/replays/<id>-<time>/{ours.json,diff.md,diff.json}; with
# several scenarios it also writes .data/botswarm/replays/<first-id>.consensus.md. A scenario
# passes when every divergence is in .data/botswarm/scenarios/<id>.baseline.json (or the bundled
# copy). Exit code: 0 all PASS, 1 any FAIL, 2 setup error.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
TOOLS="$ROOT/tools/capture-diff"
SCEN_DIRS=(".data/botswarm/scenarios" "content/other/botswarm/src/main/resources/botswarm/scenarios")
BUILD=0
RUN=1
REPEAT=1
PARALLEL=1
ACCEPT=0
IDS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --build) BUILD=1; shift ;;
        --diff-only) RUN=0; shift ;;
        --repeat) REPEAT="$2"; shift 2 ;;
        --parallel) PARALLEL="$2"; shift 2 ;;
        --accept) ACCEPT=1; shift ;;
        --manifest)
            m=""
            for d in "${SCEN_DIRS[@]}"; do [ -f "$d/$2.manifest.json" ] && m="$d/$2.manifest.json" && break; done
            [ -n "$m" ] || { echo "No manifest $2"; exit 2; }
            while read -r id; do IDS+=("$id"); done < <(py -3.12 -c "import json,sys;print('\n'.join(json.load(open(sys.argv[1]))['scenarios']))" "$m" | tr -d '\r')
            shift 2 ;;
        -h|--help) sed -n '2,/^set -e/p' "$0" | sed '$d'; exit 0 ;;
        *) IDS+=("$1"); shift ;;
    esac
done
[ ${#IDS[@]} -gt 0 ] || { sed -n '2,/^set -e/p' "$0" | sed '$d'; exit 2; }

find_file() { for d in "${SCEN_DIRS[@]}"; do [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }; done; return 0; }

[ -f game.yml ] || { echo "No game.yml: create an isolated one first (docs/bot-swarm.md)."; exit 2; }
if grep -qE '^game-port: *43594' game.yml; then
    echo "game.yml uses the shared port 43594; use an isolated config (docs/bot-swarm.md)."; exit 2
fi
# Pre-flight: every port the isolated server binds must be free (RSProx, other worktrees' servers
# and the shared dev server all hold ports on this machine).
if [ "$RUN" = 1 ]; then
    for port in $(grep -E '^ *(game-port|link-port|http-port):' game.yml | grep -oE '[0-9]+'); do
        holder="$(netstat -ano 2>/dev/null | grep -E "LISTENING" | grep -E "[:.]$port +" | awk '{print $NF}' | head -1 || true)"
        if [ -n "$holder" ]; then
            echo "Port $port in game.yml is already in use (pid $holder); pick another in game.yml."; exit 2
        fi
    done
fi
if [ "$BUILD" = 1 ]; then ./gradlew --console=plain -q :server:app:installDist; fi

JAVA="${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}"
CP="$(pwd -W 2>/dev/null || pwd)/server/app/build/install/app/lib/*"
LOG="$(mktemp)"
# Repeats go in rounds (a,b,c,a,b,c) so copies of one scenario land in different parallel batches.
QUEUE_IDS=()
for ((n = 0; n < REPEAT; n++)); do QUEUE_IDS+=("${IDS[@]}"); done
QUEUE="$(IFS=,; echo "${QUEUE_IDS[*]}")"
MARK="$(mktemp)"  # recordings newer than this belong to this run

if [ "$RUN" = 1 ]; then
    echo "==> Replaying ${#IDS[@]} scenario(s) x$REPEAT, $PARALLEL at a time"
    BOTSWARM_REPLAY="$QUEUE" BOTSWARM_REPLAY_PARALLEL="$PARALLEL" BOTSWARM_EXIT=1 \
        timeout $((300 + 120 * ${#QUEUE_IDS[@]} / PARALLEL)) \
        "$JAVA" -XX:AutoBoxCacheMax=65535 -Xms1g -Dbotswarm.allow=true -cp "$CP" \
        org.rsmod.server.app.GameServerKt > "$LOG" 2>&1 || true
    grep -E "Exception in thread|BindException|not found|could not start" "$LOG" | cut -c1-200 || true
else
    touch -d '1970-01-02' "$MARK"  # --diff-only: re-diff the latest recording of each scenario
fi

FAILED=0
DIFFS=()
for id in "${IDS[@]}"; do
    dir="$(ls -td .data/botswarm/replays/"$id"-2* 2>/dev/null | head -1 || true)"
    if [ -z "$dir" ] || [ ! "$dir/ours.json" -nt "$MARK" ]; then
        echo "!! $id: no recording (server log: $LOG)"; FAILED=1; continue
    fi
    live="$(find_file "$id.live.json")"
    baseline="$(find_file "$id.baseline.json")"
    args=(--live "$live" --ours "$dir/ours.json" --out "$dir")
    prev="$(ls -td .data/botswarm/replays/"$id"-2* 2>/dev/null | sed -n 2p || true)"
    if [ "$REPEAT" -gt 1 ] && [ -n "$prev" ] && [ "$prev/ours.json" -nt "$MARK" ]; then
        args+=(--ours2 "$prev/ours.json")
    fi
    [ -n "$baseline" ] && args+=(--baseline "$baseline")
    # --accept: today's divergences become the baseline, so later runs fail only on new ones.
    [ "$ACCEPT" = 1 ] && args+=(--write-baseline ".data/botswarm/scenarios/$id.baseline.json")
    if py -3.12 "$TOOLS/capture_diff.py" "${args[@]}" > /dev/null; then verdict=PASS; else verdict=FAIL; FAILED=1; fi
    echo "$verdict  $id  ->  $dir/diff.md"
    DIFFS+=("$dir/diff.json")
done

if [ ${#DIFFS[@]} -gt 1 ]; then
    out=".data/botswarm/replays/${IDS[0]%-[0-9][0-9]}.consensus.md"
    py -3.12 "$TOOLS/aggregate.py" --out "$out" "${DIFFS[@]}" > /dev/null
    echo "==> Consensus: $out"
fi
exit $FAILED
