#!/usr/bin/env bash
# Run every saved capture scenario against the current build and report regressions.
#
#   tools/capture-diff/nightly.sh                 # build, replay all, write the report
#   PARALLEL=8 tools/capture-diff/nightly.sh
#
# Scenarios come from tools/capture-diff/nightly.txt: one manifest name (a flow extracted with
# find_flows.py) or one scenario id per line; `#` starts a comment. Prefix a line with `serial` for
# flows with cross-player state: they interfere with each other in parallel, so they run in a
# second, one-at-a-time pass. Each scenario runs twice (flaky keys are dropped) and is diffed
# against its baseline (.data/botswarm/scenarios/<id>.baseline.json), so the report lists only
# divergences that are NEW since the baseline was accepted (`replay.sh --diff-only --accept ...`).
# Without a baseline, every divergence counts as new.
#
# Writes .data/botswarm/nightly/<date>.md and exits 1 if any scenario regressed. Needs the same
# isolated game.yml as replay.sh. It only reads the repo; scheduling it (Task Scheduler, cron, a CI
# job) is a separate, deliberate step.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
LIST="tools/capture-diff/nightly.txt"
OUT=".data/botswarm/nightly"
DATE="$(date +%Y-%m-%d-%H%M)"
mkdir -p "$OUT"

is_manifest() {
    [ -f ".data/botswarm/scenarios/$1.manifest.json" ] ||
        [ -f "content/other/botswarm/src/main/resources/botswarm/scenarios/$1.manifest.json" ]
}

PAR=()
SER=()
while read -r line; do
    line="${line%%#*}"
    line="$(echo "$line" | xargs)"
    [ -z "$line" ] && continue
    serial=0
    if [ "${line%% *}" = "serial" ]; then
        serial=1
        line="${line#serial }"
    fi
    if is_manifest "$line"; then entry=(--manifest "$line"); else entry=("$line"); fi
    if [ "$serial" = 1 ]; then SER+=("${entry[@]}"); else PAR+=("${entry[@]}"); fi
done < <(tr -d '\r' < "$LIST")
[ $((${#PAR[@]} + ${#SER[@]})) -gt 0 ] || { echo "Nothing listed in $LIST"; exit 2; }

STATUS=0
BUILD=(--build)
: > "$OUT/$DATE.log"
set +e
if [ ${#PAR[@]} -gt 0 ]; then
    bash tools/capture-diff/replay.sh "${BUILD[@]}" --repeat 2 --parallel "${PARALLEL:-6}" "${PAR[@]}" \
        | tee -a "$OUT/$DATE.log"
    [ "${PIPESTATUS[0]}" = 0 ] || STATUS=1
    BUILD=()
fi
if [ ${#SER[@]} -gt 0 ]; then
    bash tools/capture-diff/replay.sh "${BUILD[@]}" --repeat 2 --parallel 1 "${SER[@]}" \
        | tee -a "$OUT/$DATE.log"
    [ "${PIPESTATUS[0]}" = 0 ] || STATUS=1
fi
set -e

{
    echo "# Capture-diff nightly $DATE"
    echo
    echo "Build: \`$(git rev-parse --short HEAD)\` on \`$(git rev-parse --abbrev-ref HEAD)\`. Exit $STATUS."
    echo
    echo "| verdict | scenario | new divergences | report |"
    echo "|---|---|---|---|"
    grep -E "^(PASS|FAIL) " "$OUT/$DATE.log" | while read -r verdict id _ path; do
        new="$(py -3.12 -c "import json,sys;print(json.load(open(sys.argv[1]))['new'])" \
            "${path%diff.md}diff.json" 2>/dev/null || echo "?")"
        echo "| $verdict | $id | $new | $path |"
    done
    grep -E "^!! " "$OUT/$DATE.log" | sed 's/^/- /' || true
} > "$OUT/$DATE.md"
echo "==> Report: $OUT/$DATE.md"
exit $STATUS
