# Bot swarm: automated play-testing

`content/other/botswarm` logs headless bot players into a dev world. Each bot is given a goal and
plays toward it, and the run writes a report of everything that went wrong. A reasoning model (or a
developer) then triages that report.

```
goals.json ──► swarm (N headless bots in the world)
                 each bot, every 2+ ticks:
                   observe → candidate actions (≤20) → decider → queue the real client packet
                 checks watch every tick → findings
                        │
                        ▼
              .data/botswarm/runs/<run-id>/report.json + summary.md
                        │
                        ▼
              triage (Claude): dedupe → noise / known / real → GitHub issue
```

## Running it

The swarm only runs on a dev-mode realm, or when the JVM is started with `-Dbotswarm.allow=true`.
It never runs on the live world.

**In game** (admin):

| Command | Does |
|---|---|
| `::swarm start <goal\|all> [count] [decider]` | Start a run. Default count 5, default decider `prior` |
| `::swarm status` | Bots, actions, xp, finding count |
| `::swarm stop` | Finish early. The report is written once the bots have logged out |
| `::swarm goals` / `::swarm reload` | List goals / re-read goal files |

**Unattended** (nightly or CI): set these environment variables before boot.

| Variable | Example | Meaning |
|---|---|---|
| `BOTSWARM` | `fish-shrimp-draynor:5,kill-cows-lumbridge:5` | Goals and bot counts. Starts 10 cycles after boot |
| `BOTSWARM_DECIDER` | `laya` | `prior`, `uniform`, `laya`, or an `http://` URL |
| `BOTSWARM_TICKS` | `200` | Cap every goal's budget (200 ticks = 2 minutes, a PR smoke run) |
| `BOTSWARM_EXIT` | `1` | Shut the server down gracefully once the report is written |

To test without touching a shared server or database, give the worktree its own `game.yml`:

- copy `game.example.yml`, set spare ports (`game-port`, `http-port`, `central.link-port`), and leave
  `jdbc-url` empty;
- that makes `same-instance` start an embedded Postgres in the worktree's `.data/pgdata`.

## How a bot clicks

Bots are real `Player`s with a `BotClient` in place of the network client. Every action is the rsprot
message a real client would send:

| Action | Message |
|---|---|
| Interact with an object | `OpLocV2` |
| Interact with an NPC | `OpNpcV2` |
| Take a ground item | `OpObjV2` |
| Walk | `MoveGameClick` |
| Inventory item option | `If3Button` |
| Dialogue continue / option | `ResumePauseButton` |
| Enter an amount | `ResumePCountDialog` |

Each message is queued against the **real handler instance**. `PlayerInputProcess` consumes it in the
same input phase as real packets, so bots exercise every gate: `hasOp`, multiloc/multinpc resolution,
`isDelayed`, interface events. That is the point. `ProtectedAccess.opLoc*` and
`LocInteractions.interact` bypass those gates (`docs/engine-facts.md` C1) and would hide exactly the
bugs we want to find.

The options offered are what the client would show: object and NPC ops come from the type the player
sees (resolved through their vars), and inventory ops come from `interfaceOptions`.

**Bots never touch account data.** They get negative account and character ids. Every persistence
path checks `Player.isSyntheticAccount` (`characterId <= 0`) and skips them:

- the logout save (`AccountRegistry.queueLogout`);
- the DB online-session writes and Central activity log (`NetworkScript`);
- autosave (already required ids > 0).

## Deciders

A decider answers one typed question: given the goal, a short state summary and up to 20 labelled
options, it returns one probability per option. The bot samples from those probabilities with its
own seeded random. Deciders run off the world thread. A bot waits up to 10 ticks for an answer, then
falls back to the priors.

| Decider | What it is |
|---|---|
| `prior` | The catalog's own weights: goal `prefer` keywords ×12, untried options × (1 + 4·novelty), distance falloff, a penalty per previous failure |
| `uniform` | Pure fuzzing; ignores the goal |
| `laya` | `tools/bot-decider/server.py`, wrapping [Laya](https://github.com/NandhaKishorM/laya) (Apache-2.0) |

**Laya caveats:**
- It is weak zero-shot: 0.362 on its own benchmark, below a 0.461 majority baseline.
- It degrades past about 20 options. That limit is why the catalog trims to `MAX_OPTIONS = 20`.
- The sidecar therefore blends Laya's distribution with the priors (`LAYA_BLEND`, default 0.5).
- Run traces in `report.json` are the labelled data to fine-tune it on.

Any other model plugs in behind the same HTTP contract (`HttpDecider`):

```
POST /decide {"bot","goal","context","options":[...],"priors":[...]}
          -> {"probabilities":[...]}
```

## Goals

Bundled goals live in `src/main/resources/botswarm/goals.json`. To add more without a rebuild, drop
`*.json` files (one goal or an array) into `.data/botswarm/goals/`.

```json
{
  "id": "fish-shrimp-draynor",
  "description": "Net shrimp at the Draynor fishing spots",
  "start": { "x": 3087, "z": 3229, "scatter": 2 },
  "stats": { "stat.fishing": 1 },
  "inventory": [{ "obj": "obj.net" }],
  "budget_ticks": 3000,
  "success": { "type": "xp_gained", "stat": "stat.fishing", "xp": 300 },
  "prefer": ["Net"],
  "avoid": ["Trade", "Pickpocket"],
  "roam_radius": 12,
  "novelty": 0.25
}
```

- `success.type` is `xp_gained`, `stat_level` or `item_count`.
- Omit `success` for an exploration goal: it never succeeds, only times out. Raise `novelty` so it
  prefers options nobody has tried yet.

## Findings

| Kind | Severity | Signal |
|---|---|---|
| `ServerException` | High | ERROR log naming the bot (`tryOrDisconnect` and `GameExceptionLogHandler`) |
| `ForcedDisconnect` | High | Logout queued without a swarm request |
| `ClickDropped` | Medium | The client shows the op, the handler set no interaction, and the target still exists. This is silent failure #2 |
| `Stuck` | Medium | 150 ticks with no movement, xp or item change |
| `DialogueTrap` | Medium | Suspended on an input the bot cannot answer |
| `TickOverrun` | Medium | A cycle took more than 700ms |
| `Unreachable` | Info | "I can't reach that!" (mostly targets behind walls: coverage data) |
| `GoalTimeout` | Low | The budget ran out before the success condition |
| `SpawnRelocated` | Low | A login hook moved the bot more than 64 tiles from its start. Usually a Leagues region-lock eviction: give the goal its unlock `varbits` |
| `UnimplementedOp` | Info | Engine "Nothing interesting happens." The op has no script; this is coverage data, not a bug |

Repeats fold into one finding with a count. Each finding carries the bot's last 8 actions as its
reproduction trail.

`report.json` also holds:

- **coverage:** every distinct option offered and how often it was chosen, dropped, unimplemented or
  unreachable;
- **ledger:** item gains and losses attributed to the action that caused them;
- **heatmap:** tile visits;
- **tick stats;**
- **per-goal XP/hour:** the tuning signal;
- **bot traces:** every decision with its top alternatives.

## First runs (2026-10-02)

Both runs used an isolated local server (embedded Postgres, spare ports) and the `prior` decider.

**Run 1** (11 bots): every bot ended up at Varlamore (1666, 3136). The cause was the Leagues region
lock: `LeagueRegionLoginScript` evicts any player standing in a region they have not unlocked, on
every world. The fix was goal `varbits` that unlock Varlamore and Misthalin. The run also added the
`SpawnRelocated` finding, so this mistake now reports itself.

**Run 2** (13 bots, 500 ticks):

- **Chop trees:** 2 of 2 bots completed, median 253 ticks, about 6.8k XP/hour.
- **Fishing:** reached 230 of the 300 XP target before the budget ran out.
- **Cow and chicken fights:** training attack and hitpoints, and picking up loot.
- **Dialogue:** bots handled "Continue" and chat-menu options.
- **Tick time:** p95 was 615ms with 13 bots online, and no cycle exceeded 700ms.
- **Unimplemented ops:** six options the client shows have no script:
  - Doomsayer "Toggle warnings";
  - "Talk-to" on Count Check, the Ranged combat tutor and the Magic combat tutor;
  - "Enter" Death's Domain;
  - Barfy Bill "Store-axe".

## Capture diff: replaying live flows

This compares a bot's replay against real Leagues VI behaviour. The Leagues VI RSProx captures
(`docs/leagues-vi-captures.md`) record what the live server sent. A replay sends the same clicks on
our server from the same starting state, and the diff shows every difference.

**Quick start.** Run from the repo root, with an isolated `game.yml` (see below):

```bash
tools/capture-diff/replay.sh --build relic-unlock-t8          # one scenario
tools/capture-diff/replay.sh --manifest relic-confirm         # every live occurrence of a flow
tools/capture-diff/replay.sh --parallel 6 --repeat 2 --manifest spec-orb   # fast + flake-free
tools/capture-diff/nightly.sh                                  # every saved scenario, regressions only
```

Each run writes `diff.md` (the human report) and `diff.json` (the machine summary) next to its
`ours.json`. The script prints PASS or FAIL per scenario. With several scenarios it also writes a
consensus report. Exit code 0 means everything passed.

**Isolated `game.yml`.** Copy `game.example.yml` and set:

- spare ports: `game-port`, `central.link-port`, `central.http-port`;
- `league: true` and `league-type: 6` for Leagues flows;
- no `jdbc-url`, so `same-instance` starts an embedded Postgres in `.data/pgdata`.

`replay.sh` refuses to run on port 43594, so it can't collide with a shared server. It also checks
that every port in `game.yml` is free before booting, because RSProx and other worktrees' servers
hold ports too.

### Pieces

0. **`transcribe_full.py <capture-stem>`** makes a full transcript of a capture in
   `.data/botswarm/captures/`.
   - The shared transcripts in `kris-leagues-stuff` were made with a narrow RSProx preset, which
     drops player info, rebuild, stat updates, `resume_pausebutton`, walk/op clicks, chat and NPC
     info. Replays need all of these.
   - It runs RSProx with a private home, `.data/botswarm/rsprox-home`, whose single preset enables
     every packet. The private home links to your RSProx cache instead of copying it. Your RSProx
     settings and the shared `.txt` files are never touched, and tick numbers match the shared
     transcripts.
   - Each full transcript is about 100 MB. Delete `.data/botswarm/captures` to reclaim the space.
   - `find_flows.py --full` runs it automatically for every matched capture.


1. **`find_flows.py`** finds every occurrence of a flow across the transcribed captures and can
   extract each one. `--top 40` lists the most-clicked components across all captures (bank,
   prayers, shops, special attack, Colosseum, …), as a menu of flows worth testing next.
   - `--anchor` is the defining click, e.g. `league_relics:confirm_button`.
   - `--opener` is the click that opens the flow; the window starts there.
   - `--extract` writes `<name>-NN` scenarios plus `<name>.manifest.json`.

2. **`capture_extract.py`** cuts one window out of a capture. It writes `<id>.scenario.json`
   (starting state + clicks) and `<id>.live.json` (live server events + background):
   - **Starting state:** vars matching `--state-vars` (by default `league_*` and
     `side_journal_tab`), plus the exact inventory and worn slots (`inv_slots`, and the bank with
     `--seed-bank`). From a full transcript it also takes the exact start tile and every skill's
     level and XP (`stats`).
   - **Steps:** interface clicks, plus, from a full transcript: the exact Continue click
     (component + sub), amount dialogs, walk clicks, NPC/object/ground-item clicks, interface script
     triggers (raw payload, e.g. relic toggles and the pact tree), drags, and item/spell use on a
     component, object or NPC. NPCs are
     re-resolved by type near the recorded tile, because npc indexes differ between servers. Inputs
     the replay can't perform yet (item on ground item or player, name/string/item dialogs, minimap
     clicks) are listed under `unsupported`.
   - **Combat:** `anim`, `spotanim` and `hit` events for the player, and for the NPCs it engaged
     on live (NPCs it hit or faced). The NPC types go in the live file's `engaged` list. On our side
     the recorder samples the same facts on `LateCycle`, before post-tick clears them. NPC hitmarks
     can't be read back on our server, so a hitpoint drop stands in for "hit on NPC". Hits compare
     who was hit and the hitmark type, not damage: damage rolls are random.
   - **Extra events compared:** position changes, skill levels and chat messages. Levels are
     compared but not XP: RSProx 1.0.5 decodes post-login stat XP unreliably, so only the login
     totals are trusted (for seeding).
   - **Background:** every cs2 and var id the capture already shows in the 30 ticks before the
     flow (cooldown bars, clocks). The diff drops these.
   - **Setup clicks:** `--setup=tick:iface:comp` adds unrecorded clicks during warmup, e.g.
     `--setup=-3:629:34` opens the journal's Leagues tab, because our league login resets it to
     Quests.
   - **Continue steps:** the transcript does not record `resume_pausebutton`, so a chat-modal close
     with no button that tick becomes a `continue` step.
   - **Scope:** `--scope 655,656,193,229,660` lists the interfaces that make up the flow. Clicks on
     other interfaces (the player banking mid-flow) are not replayed, and those interfaces are not
     diffed.

3. **Replay (server)** with `BOTSWARM_REPLAY=a,b,c BOTSWARM_EXIT=1` (queued, one boot), or
   `::swarm replay <id>` in game.
   - Vars are applied before registration, as real account vars are. Stats, inventory, worn and
     bank are seeded exactly, and the bot spawns on the recorded tile.
   - Every server->client message is recorded to `ours.json`: varbit/varp, open/close, setevents,
     cs2, settext/setobject/sethide, inventory slots, chat.
   - Each step notes the If3Button gate that would drop it: the interface isn't open, or the op
     isn't enabled.
   - `companions` adds unrecorded second players running their own steps. They expose state that
     is shared between players.
   - Each step says what the server did with the click. "[dropped by handler]" means no
     interaction was set (an option gate), and "not found" means the NPC isn't near the recorded
     tile.
   - **Parallel:** `BOTSWARM_REPLAY_PARALLEL` (`replay.sh --parallel N`) runs N replays at once in
     one world. 24 replays took 1m23s, including boot. Flows with cross-player state interfere with
     each other in parallel, so use `--repeat 2`, which marks that interference as flaky.

4. **`capture_diff.py`** compares the two:
   - Events match per key within ±2 ticks, and each is reported as match, shifted, different,
     missing (live only) or extra (ours only).
   - A second pass catches mistimed events: the same event and value outside the tolerance. For
     example, an item granted 5 ticks earlier than live becomes one mistimed row instead of a
     missing row plus an extra row.
   - The report is grouped by the replayed step that caused each event.
   - **End state:** the final value of every var live changed is compared with ours. This catches
     state that ends up different even when the event streams are hard to line up.
   - **Flakiness:** `--ours2` takes a second recording of the same scenario, and keys that differ
     between our own two runs are excluded. `replay.sh --repeat 2` does this automatically, e.g.
     boosted levels decaying on a world timer the replay doesn't control.
   - The verdict is **FAIL** if any divergence is missing from the baseline. `--write-baseline`
     accepts the current divergences into `<id>.baseline.json`, which turns a scenario into a
     regression test. `--strict-timing` also fails on shifted events.

5. **Baselines and nightly:**
   - `replay.sh --diff-only --accept ...` stores the current divergences as each scenario's
     accepted baseline. Later runs then fail only on new divergences.
   - `nightly.sh` replays everything listed in `tools/capture-diff/nightly.txt` twice, in
     parallel, and writes `.data/botswarm/nightly/<date>.md`. It exits 1 if anything regressed.
   - Scheduling it (Task Scheduler, cron or CI) is left as a deliberate, separate step.

6. **`aggregate.py`** combines several `diff.json` files by divergence shape (status, kind and key,
   ignoring values that naturally differ, such as which relic was picked). It ranks each shape by
   how many runs show it (shapes in under half the runs are folded away with `--min-share`, and
   inventory rows fold per inventory, not per slot). A divergence seen in every live example is behaviour; one seen in a single
   example is incidental.

Bundled scenarios live in `resources/botswarm/scenarios/`: `relic-unlock-t8` (with its
`.live.json`) and `relic-unlock-t8-two-players`.

**First findings (relic unlock, tier 8):**

| | Finding | Evidence |
|---|---|---|
| Bug | `PactsScript.lastViewedRelicSlot` is one field shared by all players. Another player viewing a relic changes what your Confirm commits | Two-player replay: the bot confirmed the Flask of Fervour and was given Executioner (`league_relic_selection_7` = 1 instead of 3, plus a Sage's axe). Live stores this per player in `league_relic_last_viewed` (10048), which we never set |
| Flow | Confirm differs from live | Live closes the relic interface, grants the item 5 ticks later through an `objectbox` dialogue and the "Relic Unlocked!" notification (cs2 3343), refreshes the side panel, then reopens the relic interface after Continue. We keep the interface open, grant instantly and send three chat lines, one of which ("You commit to…") does not exist on live |
| Data | Tier-8 detail view shows the wrong passive | cs2 3193 arg 20: live 1152 ("There are no passive unlocks for this tier."), ours 1144 (the 5x XP passive) |
| Events | Interface enables differ from live | clickzones `0..23` live vs `0..63`; toggles `-1..-1` vs `-1..63`; live enables `league_menu_frame 10..20` and we don't; we enable `confirm_button` and live doesn't; cs2 2524 args `(-1,-3)` live vs `(-1,-1)` |
| Engine | cs2 1749 sent to every player every tick | `PlayerRegionProcessor.assignLastKnownNormalCoord` publishes `CoordsMovedEvent` every tick without checking that coords changed, and `WorldMapScript` answers each one. Live sends none while the player stands still |
| Login | League worlds reset the journal tab | `PactsScript` sets `side_journal_tab` to Quests on every league login. Live keeps the player's tab |
| Rule | Relics that give items need a bank or the Leagues Tutor nearby | In 3 of 10 live confirms the server refused the unlock with the messagebox "You must be within a bank or near the Leagues Tutor to unlock this as it gives you items." We grant anywhere |

Consensus over all 10 live relic confirms (`find_flows.py` then `replay.sh --manifest relic-confirm`):

- **Every run:** the relic interface closes on confirm, and cs2 1749 is sent every tick.
- **8 or 9 of 10:** the notification, the side-panel refresh, `league_menu_frame` events, the click
  ranges and cs2 2524 args.

So the flow differences above are consistent live behaviour, not quirks of one capture.

### More findings (combat and prayer families, 2026-10-02)

Six special-attack flows and six quick-prayer flows, each replayed twice in parallel:

- **Quick prayer matches live.** On the orb click, the same three varbits change on the same tick.
- **Fire giants can't be fought.** Our server accepts the attack click (an interaction is set), but
  no combat follows. This looks like the silent-failure pattern of an NPC option bound to the wrong
  id.
- **Cave horrors.** We block the attack without a witchwood icon. On live, the player fought one
  without an icon.
- **Missing NPCs.** The NPC the live player fought doesn't exist near that tile on our server, in
  3 of 6 spec flows (Varlamore avium giants, a canafis werewolf, a clue-trail NPC).
- **Barrows.** Live answers "You don't find anything." when a sarcophagus search finds nothing. We
  send nothing.
- **Recorder self-check:** a hand-written cow fight records player and NPC animations and hits
  both ways.

## Where this is going

These are drawn from how other studios use bot swarms:

| Idea | Taken from | Next step |
|---|---|---|
| PR smoke run | Rare (Sea of Thieves), Mojang | `BOTSWARM_TICKS=200`, 5 bots per core goal, as a CI check on `M-Live-Test` PRs |
| Nightly soak | EA DICE (Battlefield V AutoPlayers) | 50+ bots for hours; watch tick p95, memory and the exception rate |
| Coverage and stuck maps | EA SEED | Render the heatmap and op coverage per region; outliers are collision or route bugs |
| Tuning report | King (Candy Crush) | Compare goal XP/hour with OSRS wiki rates; bots answer the `// tunable` questions in PRs |
| Economy ledger | MMO economy simulation | Sources and sinks per hour; a conservation break means a dupe (private issue only) |
| Human-like priors | Ubisoft (Rainbow Six Siege) | Derive action frequencies from the Leagues VI RSProx captures and fine-tune Laya on them |
| Replay | Deterministic replay testing | Replay a finding's action trail to verify a fix and keep it as a regression test |
| Bug report to repro | BugCraft (Minecraft) | Claude turns a player bug report into a goal; the swarm reproduces it |
| LLM oracle | TITAN (MMORPG testing) | Claude reviews traces for wrong rewards and dialogue that hard checks miss |

## Known gaps

- **Bots are invisible to real clients.** No rsprot PlayerInfo avatar is allocated. Server-side
  logic (aggression, combat, player scans) still sees them. Visibility needs a session-less
  `ClientCycle`.
- **Replay is not deterministic yet.** Choices are reproducible from the seed and the recorded
  distributions, but world RNG and other players are not.
- **The variant-bound NPC op silent failure** (`docs/engine-facts.md` C3) is not detected yet. In that
  case the click is accepted and then nothing happens, with no chat message.
- **Unhandled input dialogs.** Only pause-button and count dialogs are answered. Name, string and
  object dialogs report `DialogueTrap`.
- **Shops, bank and Grand Exchange:** no interface-driven actions beyond the inventory yet.
