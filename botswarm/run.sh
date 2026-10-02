#!/usr/bin/env bash
# One-command bot swarm run against an OpenRune/rsmod server checkout.
#
# Builds a throwaway git worktree of the server, drops the swarm module in, applies the
# synthetic-account patch, runs on spare ports with its own embedded Postgres, then copies the
# report into ./runs/. The server checkout you point at is never modified.
#
#   ./run.sh --server ../relicscape-server
#   ./run.sh --server ../relicscape-server --goals explore-lumbridge:5 --ticks 300 --decider uniform
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SERVER=""
REF="HEAD"
GOALS="fish-shrimp-draynor:3,kill-cows-lumbridge:3,chop-trees-lumbridge:2,explore-lumbridge:3,kill-chickens-lumbridge:2"
TICKS=500
DECIDER="prior"
MODULE="$HERE/server-module"
KEEP=0
GAME_PORT="${BOTSWARM_GAME_PORT:-43610}"
LINK_PORT="${BOTSWARM_LINK_PORT:-9190}"
HTTP_PORT="${BOTSWARM_HTTP_PORT:-8091}"

usage() {
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    echo "Options: --server <path> (required)  --ref <git ref, default HEAD>  --goals <goal:n,...>"
    echo "         --ticks <budget cap, default 500>  --decider <prior|uniform|laya|http://...>"
    echo "         --module <path to server-module>  --keep (leave the worktree for inspection)"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --server) SERVER="$2"; shift 2 ;;
        --ref) REF="$2"; shift 2 ;;
        --goals) GOALS="$2"; shift 2 ;;
        --ticks) TICKS="$2"; shift 2 ;;
        --decider) DECIDER="$2"; shift 2 ;;
        --module) MODULE="$2"; shift 2 ;;
        --keep) KEEP=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 2 ;;
    esac
done

[ -n "$SERVER" ] || { usage; exit 2; }
SERVER="$(cd "$SERVER" && pwd)"
[ -d "$MODULE/src" ] || { echo "No swarm module at $MODULE"; exit 2; }
ls "$SERVER"/.data/*.key >/dev/null 2>&1 || { echo "No .data/*.key in $SERVER (needed to boot)"; exit 2; }

if [ -n "${JAVA:-}" ]; then :;
elif [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java";
else JAVA="java"; fi

winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

BASE="$(mktemp -d)"
WT="$BASE/server"
cleanup() {
    if [ "$KEEP" = 0 ]; then
        git -C "$SERVER" worktree remove --force "$WT" >/dev/null 2>&1 || true
        rm -rf "$BASE"
    else
        echo "Worktree kept at $WT (remove with: git -C \"$SERVER\" worktree remove --force \"$WT\")"
    fi
}
trap cleanup EXIT

echo "==> Worktree of $SERVER at $REF"
git -C "$SERVER" worktree add --detach "$WT" "$REF" >/dev/null

echo "==> Installing swarm module and synthetic-account patch"
cp -r "$MODULE" "$WT/content/other/botswarm"
rm -rf "$WT/content/other/botswarm/build"
if [ ! -f "$WT/engine/game/src/main/kotlin/org/rsmod/game/entity/player/SyntheticAccount.kt" ]; then
    git -C "$WT" apply "$HERE/patches/synthetic-accounts.patch"
fi
mkdir -p "$WT/.data"
cp "$SERVER"/.data/*.key "$WT/.data/"

# Isolated config: spare ports, empty jdbc-url => same-instance embedded Postgres in $WT/.data/pgdata.
cat > "$WT/game.yml" <<EOF
name: "BotSwarm"
game-port: $GAME_PORT
revision: 238
environment: "LIVE"
world: 255
central:
  same-instance: true
  host: "127.0.0.1"
  link-port: $LINK_PORT
  http-port: $HTTP_PORT
  postgres:
    pool-size: 10
EOF

echo "==> Building cache and server (first run downloads the rev 238 cache, ~4 min)"
(cd "$WT" && ./gradlew --console=plain -q :or-cache:freshCache :server:app:installDist)

echo "==> Running swarm: $GOALS (cap $TICKS ticks, decider $DECIDER)"
LOG="$BASE/server.log"
CP="$(winpath "$WT/server/app/build/install/app/lib")/*"
(
    cd "$WT"
    BOTSWARM="$GOALS" BOTSWARM_TICKS="$TICKS" BOTSWARM_DECIDER="$DECIDER" BOTSWARM_EXIT=1 \
        "$JAVA" -XX:AutoBoxCacheMax=65535 -Xms1g -Dbotswarm.allow=true \
        -cp "$CP" org.rsmod.server.app.GameServerKt > "$LOG" 2>&1
) || true
grep -E "Bot swarm|Exception in thread|BindException" "$LOG" | cut -c1-220 || true

RUNS="$WT/.data/botswarm/runs"
if [ ! -d "$RUNS" ] || [ -z "$(ls -A "$RUNS")" ]; then
    echo "No report was written. Server log: $LOG"
    KEEP=1
    exit 1
fi
mkdir -p "$HERE/runs"
cp -r "$RUNS"/* "$HERE/runs/"
cp "$LOG" "$HERE/runs/$(ls "$RUNS" | tail -1)/server.log"
LATEST="$HERE/runs/$(ls "$RUNS" | tail -1)"
echo
cat "$LATEST/summary.md"
echo
echo "Full report: $LATEST/report.json"
