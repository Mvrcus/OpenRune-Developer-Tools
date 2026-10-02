# Bot swarm

Headless test bots for an OpenRune/rsmod game server. Each bot logs into the world with no client,
is given a goal (net shrimp, kill cows, explore Lumbridge…) and plays toward it. The run ends with a
report of everything that went wrong. Claude or a developer then triages that report into issues.

This folder is self-contained. Nothing here is part of the RuneLite plugin build.

| Path | What it is |
|---|---|
| `run.sh` | One command: build a throwaway server worktree, run the swarm, copy the report to `runs/` |
| `server-module/` | The server plugin module, dropped in as `content/other/botswarm` |
| `patches/synthetic-accounts.patch` | Engine guard: accounts with negative ids are never saved or reported to Central |
| `decider/server.py` | Optional decision sidecar wrapping [Laya](https://github.com/NandhaKishorM/laya) (Apache-2.0) |
| `triage/SKILL.md` | Claude skill: turn a run report into proposed GitHub issues |
| `capture-diff/` | Replay real RSProx captures with a bot and diff the server's responses against live |
| `docs/bot-swarm.md` | Full design notes, the capture-diff guide and example findings |

## Capture diff

Replays what a real player did in an RSProx capture and diffs every packet your server sends back
against what the live server sent. The scripts live in `capture-diff/` and are copied into a server
checkout's `tools/capture-diff/` (paths to captures and RSProx are set at the top of each script).

```bash
py -3.12 capture-diff/find_flows.py --top 40                     # what players click most
py -3.12 capture-diff/find_flows.py --anchor "orbs:specbutton$" --extract --full --name spec-orb
tools/capture-diff/replay.sh --parallel 6 --repeat 2 --manifest spec-orb
```

Each run reports, per click, what live did versus what the server did: missing, extra, different
or mistimed events, end-state var differences and combat (hits, animations), with flaky keys
dropped. A consensus over every live example of a flow separates real behaviour from one-offs. See
`docs/bot-swarm.md` for the full guide.

## Run it

```bash
./botswarm/run.sh --server ../relicscape-server
```

Requirements: Git Bash or another POSIX shell, JDK 21 (`JAVA_HOME`, or `java` on `PATH`), and a
server checkout that has its `.data/*.key` files.

What the script does:

1. Creates a detached git worktree of the server in a temporary directory. The server checkout you
   point at is never modified.
2. Copies the module in and applies the patch.
3. Writes an isolated `game.yml`: ports 43610, 9190 and 8091, and an embedded Postgres in the
   worktree. To use other ports, set `BOTSWARM_GAME_PORT`, `BOTSWARM_LINK_PORT` or
   `BOTSWARM_HTTP_PORT`.
4. Runs `freshCache` and `installDist`.
5. Boots the server with the swarm and waits for it to exit.
6. Prints `summary.md` and copies the run to `botswarm/runs/<run-id>/`.

Plan for about 4 minutes of cache download on the first run, plus the swarm itself (500 ticks is
about 5 minutes).

| Option | Default | |
|---|---|---|
| `--goals` | 5 Lumbridge/Draynor goals, 13 bots | `goal:count,goal:count` |
| `--ticks` | `500` | Budget cap per goal; 600 ticks = 6 minutes |
| `--decider` | `prior` | `prior` (goal-weighted), `uniform` (fuzz), `laya`, or an `http://` URL |
| `--ref` | `HEAD` | The server git ref to test |
| `--keep` | off | Keep the worktree and server log for inspection |

To use Laya, start the sidecar first and pass `--decider laya`:

```bash
py -3.12 -m pip install laya
py -3.12 botswarm/decider/server.py
```

## What a bot does

Every click is the rsprot packet a real client sends, queued against the real packet handler and
consumed in the normal input phase:

| Action | Packet |
|---|---|
| Interact with an object | `OpLocV2` |
| Interact with an NPC | `OpNpcV2` |
| Take a ground item | `OpObjV2` |
| Walk | `MoveGameClick` |
| Inventory item option | `If3Button` |
| Dialogue continue / option | `ResumePauseButton` |
| Enter an amount | `ResumePCountDialog` |

Bots therefore hit the same gates a player does, including clicks that are silently dropped when an
op is bound to the wrong multiloc or multinpc id.

The options a bot can choose are what the client would show. A decider picks among up to 20 of
them, weighted by the goal's `prefer` keywords, curiosity (untried options) and distance.

## The report

`runs/<run-id>/summary.md` is a digest. `report.json` holds everything:

| Section | Contents |
|---|---|
| `findings` | Each finding with a kind, severity, count and the bot's last 8 actions |
| `goals` | Completion, median ticks to complete, XP/hour per stat |
| `coverage` | Every distinct option offered: chosen, dropped, unimplemented, unreachable |
| `ledger` | Item gains and losses attributed to the action that caused them |
| `tick_stats`, `heatmap` | Cycle times and tiles visited |
| `bots[].trace` | Every decision with its top alternatives |

| Finding | Meaning |
|---|---|
| `ServerException` / `ForcedDisconnect` | An error was logged while processing a bot |
| `ClickDropped` | The client shows an op, but the handler dropped it while the target was still there |
| `Stuck` / `DialogueTrap` | No progress for 150 ticks / an input the bot cannot answer |
| `TickOverrun` | A cycle took more than 700ms |
| `UnimplementedOp` | "Nothing interesting happens.": the op has no script (coverage) |
| `Unreachable`, `GoalTimeout`, `SpawnRelocated` | Coverage and test-setup signals |

To triage a run, copy `triage/SKILL.md` into the server repo's `.claude/skills/swarm-triage/` and ask
Claude to "triage the swarm". It proposes issues and files none until you confirm.

## In game

With the module installed on a dev-mode world, admins can run:

```
::swarm start <goal|all> [count] [decider]
::swarm status
::swarm stop
::swarm goals
```

## Compatibility

Developed and tested against `relicscape-server` (an OpenRune-Server fork) at `M-Live-Test`
`ea901d559`. The module uses that fork's `XpListeners` hook and its Central/DB `NetworkScript`.
Other forks may need small adjustments to the patch.

Bundled goals set the Leagues VI area-selection varbits (`10662`/`10663`). Without them, that fork's
region lock evicts bots to Varlamore at login. On a server without region locks they are harmless.

## Known gaps

- Bots are invisible to real clients (no PlayerInfo avatar).
- Replay is not deterministic yet.
- Name, string and object input dialogs are not answered.
- No shop, bank or Grand Exchange actions yet.
