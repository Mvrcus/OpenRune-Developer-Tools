---
name: swarm-triage
description: Triage a bot swarm run report (.data/botswarm/runs/<id>/report.json) into GitHub issues for developers. Use after a swarm run, when asked to "triage the swarm", "file the bot findings", or to review what the bots found.
---

# Bot swarm triage

Turn one swarm run into a short list of actionable issues. The swarm is documented in
`docs/bot-swarm.md`.

## 1. Load the run

- With no run named, use the newest `.data/botswarm/runs/*/`.
- Read `summary.md` first, then `report.json`. The report is large, so read fields selectively:
  `findings`, `goals`, `tick_stats`, `coverage.entries` filtered to `dropped > 0` or
  `unreachable > 0`, and the `trace` of any bot a finding names.

## 1b. Capture-diff runs

Capture-diff output lives in `.data/botswarm/replays/`. Each recording has its own
`<scenario>-<time>/diff.md` and `diff.json`, and a consensus report sits alongside them as
`<flow>.consensus.md`.

- **Start from the consensus.** A divergence seen in every live example (or most of them) is real
  behaviour to fix. One seen in a single example is incidental to that capture.
- **Read divergences against the live capture.** Live is the reference: "missing" means live sent
  it and we didn't; "end state" means a var finishes with a different value.
- **Before calling a divergence real, check the replay's own steps** in `diff.md`. If a step reads
  "not open", "not enabled" or "not found", the replay itself failed, and the divergences after it
  say nothing about the game.
- **Ignore anything listed under "Flaky on our server".**

## 2. Classify every finding

Assign one of these:

- **Real bug.** Reproducible from the finding's `recent_actions` and the code. Open the code it
  points at and confirm the cause before calling it real. For example:
  - a `ClickDropped` op whose bound handler sits on the multiloc parent (`CLAUDE.md` silent failure
    #2);
  - a `ServerException` whose stack names content code.
- **Content gap.** `UnimplementedOp` on content nobody has written yet. Do not file these one by
  one: group them by area into a single "unimplemented ops near X" checklist issue, and only when
  the user asks.
- **Harness noise.** A limit of the bots, not the game. Recognise it by:
  - `DialogueTrap` on an input type the bot cannot answer;
  - `GoalTimeout` with an obviously wrong start tile;
  - `SpawnRelocated` from a goal missing its region-unlock varbits;
  - `Stuck` while a long skilling action was running.

  Report noise back to the user as fixes to the goals or the harness, not as issues.
- **Tuning signal.** Goal XP/hour far off OSRS wiki rates. List these for the developer. They are
  not bugs.

## 3. Check what is already known

```bash
gh issue list -R relicscape/relicscape-server --state open --search "<key terms>"
```

Always pass `-R relicscape/relicscape-server`: a bare `gh` targets the upstream fork.

## 4. Propose, then file

1. Show the user a table of the issues you would file: title, severity, evidence, suspected file.
2. File nothing until they confirm.
3. Each issue body contains:
   - what the bot did: its last actions, the coordinates, the run id and the bot seed;
   - the observed signal;
   - the suspected cause, with `file:line`;
   - how to reproduce it: the goal plus `::swarm start <goal> 1`, or the trace.

**Security:** a finding that implies item duplication, free items, or bypassing a requirement or
region lock is an economy/exploit bug.

- Never put its details in a public issue title or in Player Notes.
- Tell the user and let them choose where it goes.
