"""Cut one flow out of a transcribed RSProx capture into a replay scenario + the live event trace.

    py -3.12 tools/capture-diff/capture_extract.py \
        --capture .data/botswarm/captures/20260420T202435-d8f0250.txt \
        --from-tick 23944 --to-tick 23996 --name relic-unlock-t8 --setup=-3:629:34 \
        --scope 655,656,193,229,660 --out .data/botswarm/scenarios

Writes <out>/<name>.scenario.json (starting state + client steps, ticks relative to --from-tick)
and <out>/<name>.live.json (server->client events in the window, same tick base). The bot swarm
replays the scenario (`BOTSWARM_REPLAY` / `::swarm replay`) and capture_diff.py compares.

Works on any transcript, but a FULL one (see transcribe_full.py) gives far more:

| from the capture       | becomes                                                          |
|------------------------|------------------------------------------------------------------|
| player_info / rebuild  | exact start position; `pos` events (movement is diffed)          |
| update_stat_v2         | start levels + XP; `stat` events (XP gains are diffed)           |
| update_inv_full/partial| exact inventory / worn (and with --seed-bank, bank) slots        |
| if_buttonx             | `button` steps                                                   |
| resume_pausebutton     | `continue` steps on the exact component + sub                    |
| resume_p_countdialog   | `count` steps                                                    |
| move_gameclick         | `walk` steps                                                     |
| opnpcN / oplocN / opobj| `opnpc` / `oploc` / `take` steps (npcs re-resolved by id near the |
|                        | recorded tile: indexes differ between servers)                   |
| if_script_trigger      | `script` steps (component, crc and raw payload bytes)            |
| if_buttond / buttont   | `drag` / `use_on_com` steps                                      |
| oploct / opnpct        | `use_on_loc` / `use_on_npc` steps (item or spell on a target)     |
| message_game           | `message` events                                                 |
| player/npc info ext.   | `anim`, `spotanim` and `hit` events for the player and for the npcs |
|                        | it engages (hit, or faced); needs the everything preset         |

Older transcripts made with a narrow filter preset have no resume_pausebutton; a chat-modal close
with no click that tick is then inferred as a `continue` step. Inputs the replay cannot perform
yet (item-on-ground-item, item-on-player, name/string/obj dialogs, minimap clicks) are listed
under "unsupported" in the scenario.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

TICK = re.compile(r"^\[(\d+)\]\s*$")
EVENT = re.compile(r"^\s{4}\[([a-z_0-9]+)\]\s*(.*)$")
SUB_EVENT = re.compile(r"^\s{8}\[([a-z_]+)\] (.*)$")
NESTED = re.compile(r"^\s{12}\[(sequence|spotanim|hit|face_pathingentity)\] (.*)$")
NPC_REF = re.compile(r"npc=\(index=(\d+), id=[^\s(]+ \((\d+)\)")
COM = re.compile(r"com=([^\s(]+) \((-?\d+):(-?\d+)\)")
ID_PAREN = re.compile(r"id=([^\s(]+) \((-?\d+)\)")
OBJ_PAREN = re.compile(r"obj=[^\s(]+ \((-?\d+)\)")
COORD = re.compile(r"coord=\((\d+), (\d+), (\d+)\)")
NEWCOORD = re.compile(r"newcoord=\((\d+), (\d+), (\d+)\)")
PLAYER = re.compile(r"player=\(index=(\d+),")
INV_LINE = re.compile(r"^\s+- (?:slot=(\d+), )?id=(?:[^\s(]+ \((-?\d+)\)|(-1)), count=(-?\d+)")
NAMED_COM = re.compile(r"^[^\s(]+ \((-?\d+):(-?\d+)\)$")
NAMED_ID = re.compile(r"^[^\s(]+ \((-?\d+)\)$")
STAT = re.compile(r"stat=(\w+), level=([\d,]+), experience=([\d,]+)")
OP_TAG = re.compile(r"^op(npc|loc|obj)(\d)(?:_v2)?$")
UNSUPPORTED_INPUTS = ("opobjt", "opplayert", "resume_p_namedialog", "resume_p_stringdialog",
                      "resume_p_objdialog", "move_minimapclick")


def field_com(rest: str, key: str) -> str | None:
    """`key=name (iface:comp)` -> "iface:comp"."""
    m = re.search(rf"\b{key}=[^\s(]+ \((-?\d+):(-?\d+)\)", rest)
    return f"{m.group(1)}:{m.group(2)}" if m else None


def field_id(rest: str, key: str) -> int:
    """`key=name (id)` or `key=id` -> id, else -1."""
    m = re.search(rf"\b{key}=(?:[^\s(,]+ \((-?\d+)\)|(-?\d+))", rest)
    if not m:
        return -1
    return int(m.group(1) if m.group(1) is not None else m.group(2))

DEFAULT_STATE_VARS = r"^(league_|side_journal_tab$)"
SEEDED_INVS = (93, 94)  # inventory, worn; --seed-bank adds 95


def num(text: str) -> int:
    return int(text.replace(",", ""))


def kv(text: str) -> dict[str, str]:
    """Loose `key=value, key=value` parser that respects [..], (..) and "..". """
    out: dict[str, str] = {}
    key, buf, depth, quoted = None, "", 0, False
    for c in text:
        if c == '"' and depth == 0:
            quoted = not quoted
        if not quoted:
            if c in "[(":
                depth += 1
            elif c in "])":
                depth -= 1
        if c == "=" and key is None and depth == 0 and not quoted:
            key, buf = buf.strip(), ""
        elif c == "," and depth == 0 and not quoted and key is not None:
            out[key] = buf.strip()
            key, buf = None, ""
        else:
            buf += c
    if key is not None:
        out[key] = buf.strip()
    return out


def split_values(text: str) -> list[str]:
    """Split a cs2 `values=[...]` body on top-level commas."""
    out, buf, depth, quoted = [], "", 0, False
    for c in text:
        if c == '"':
            quoted = not quoted
        if not quoted and c == "(":
            depth += 1
        elif not quoted and c == ")":
            depth -= 1
        if c == "," and depth == 0 and not quoted:
            out.append(buf.strip())
            buf = ""
        else:
            buf += c
    if buf.strip():
        out.append(buf.strip())
    return out


def norm_cs2_arg(value: str) -> str:
    """Full transcripts name script args (`league_relics:view_all (655:14)`, `obj (1234)`); the
    server sends raw ints (component = interface << 16 | component). Normalise to the raw form."""
    m = NAMED_COM.match(value)
    if m:
        return str((int(m.group(1)) << 16) | (int(m.group(2)) & 0xFFFF))
    m = NAMED_ID.match(value)
    if m:
        return m.group(1)
    return {"true": "1", "false": "0"}.get(value, value)  # booleans travel as ints


def parse_event(tag: str, rest: str) -> dict | None:
    if tag in ("varbit", "varp_small", "varp_large"):
        m = ID_PAREN.search(rest)
        if not m:
            return None
        fields = kv(rest)
        return {"kind": "varbit" if tag == "varbit" else "varp", "id": int(m.group(2)),
                "name": m.group(1), "old": num(fields.get("old", "0") or "0"),
                "new": num(fields.get("new", "0") or "0")}
    if tag == "update_stat_v2":
        # Numbers are thousands-separated ("experience=27,926,213"), so no generic kv split.
        m = STAT.search(rest)
        return {"kind": "stat", "stat": m.group(1), "level": num(m.group(2)),
                "xp": num(m.group(3))} if m else None
    if tag == "message_game":
        return {"kind": "message", "text": kv(rest).get("message", "").strip('"')}
    if tag == "rebuild_normal_v2" or tag.startswith("rebuild_login"):
        m = re.search(r"localplayercoord=\((\d+), (\d+), (\d+)\)", rest)
        return {"kind": "rebuild", "coord": tuple(int(x) for x in m.groups())} if m else None
    com = COM.search(rest)
    target = f"{com.group(2)}:{com.group(3)}" if com else None
    name = com.group(1) if com else None
    if tag == "if_buttonx":
        fields = kv(rest)
        obj = OBJ_PAREN.search(rest)
        return {"kind": "button", "com": target, "name": name, "sub": int(fields.get("sub", "-1")),
                "obj": int(obj.group(1)) if obj else -1, "op": int(fields.get("op", "1"))}
    if tag == "resume_pausebutton":
        return {"kind": "continue", "com": target, "name": name,
                "sub": int(kv(rest).get("sub", "-1"))}
    if tag == "resume_p_countdialog":
        m = re.search(r"count=(-?[\d,]+)", rest)
        return {"kind": "count", "count": num(m.group(1)) if m else 0}
    if tag == "move_gameclick":
        m = COORD.search(rest)
        return {"kind": "walk", "x": int(m.group(1)), "z": int(m.group(2)),
                "level": int(m.group(3))} if m else None
    op = OP_TAG.match(tag)
    if op:
        target_kind, op_num = op.group(1), int(op.group(2))
        m, ident = COORD.search(rest), ID_PAREN.search(rest)
        if not m or not ident:
            return None
        step = {"x": int(m.group(1)), "z": int(m.group(2)), "level": int(m.group(3)), "op": op_num}
        if target_kind == "npc":
            return {"kind": "opnpc", "npc": int(ident.group(2)), "name": ident.group(1), **step}
        if target_kind == "loc":
            return {"kind": "oploc", "loc": int(ident.group(2)), "name": ident.group(1), **step}
        return {"kind": "take", "obj": int(ident.group(2)), "name": ident.group(1), **step}
    if tag == "if_script_trigger":
        m = re.search(r"payload=\[([^\]]*)\]", rest)
        payload = [int(b) for b in m.group(1).split(",") if b.strip()] if m else []
        return {"kind": "script", "com": target, "name": name, "sub": field_id(rest, "sub"),
                "obj": field_id(rest, "obj"), "crc": field_id(rest, "crc"), "payload": payload}
    if tag in ("if_buttond", "if_buttont"):
        return {"kind": "drag" if tag == "if_buttond" else "use_on_com",
                "com": field_com(rest, "selectcom"), "sub": field_id(rest, "selectedsub"),
                "obj": field_id(rest, "selectedobj"), "target": field_com(rest, "targetcom"),
                "target_sub": field_id(rest, "targetsub"), "target_obj": field_id(rest, "targetobj")}
    if tag in ("oploct", "opnpct"):
        m, ident = COORD.search(rest), ID_PAREN.search(rest)
        if not m or not ident:
            return None
        step = {"x": int(m.group(1)), "z": int(m.group(2)), "level": int(m.group(3)),
                "com": field_com(rest, "com"), "sub": field_id(rest, "sub"),
                "obj": field_id(rest, "obj"), "name": ident.group(1)}
        if tag == "oploct":
            return {"kind": "use_on_loc", "loc": int(ident.group(2)), **step}
        return {"kind": "use_on_npc", "npc": int(ident.group(2)), **step}
    if tag in UNSUPPORTED_INPUTS:
        return {"kind": "unsupported", "tag": tag, "detail": rest[:160]}
    if tag in ("if_opensub", "if_closesub"):
        m = ID_PAREN.search(rest)
        ev = {"kind": tag, "target": target, "iface": int(m.group(2)) if m else -1,
              "name": m.group(1) if m else None}
        if tag == "if_opensub":
            ev["type"] = kv(rest).get("type", "")
        return ev
    if tag == "if_setevents_v2":
        fields = kv(rest)
        events = [e.strip() for e in fields.get("events", "[]").strip("[]").split(",") if e.strip()]
        return {"kind": "if_setevents", "com": target, "name": name,
                "start": int(fields["start"]), "end": int(fields["end"]), "events": events}
    if tag == "runclientscript":
        fields = kv(rest)
        ident = fields["id"]
        m = NAMED_ID.match(ident)
        values = [norm_cs2_arg(v) for v in split_values(fields.get("values", "[]")[1:-1])]
        return {"kind": "cs2", "id": int(m.group(1)) if m else int(ident), "args": ", ".join(values)}
    if tag == "if_settext":
        return {"kind": "if_settext", "com": target, "name": name,
                "text": kv(rest).get("text", "").strip('"')}
    if tag == "if_setobject":
        m = ID_PAREN.search(rest)
        return {"kind": "if_setobject", "com": target, "name": name,
                "obj": int(m.group(2)) if m else -1}
    if tag == "if_sethide":
        fields = kv(rest)
        return {"kind": "if_sethide", "com": target, "name": name,
                "hidden": fields.get("hidden", fields.get("hide", "")).lower() == "true"}
    if tag in ("update_inv_partial", "update_inv_full"):
        m = ID_PAREN.search(rest)
        return {"kind": "inv_header", "inv": int(m.group(2)) if m else -1}
    return None


STEP_KINDS = ("button", "continue", "count", "walk", "opnpc", "oploc", "take", "script", "drag",
              "use_on_com", "use_on_loc", "use_on_npc")


def extract(capture: str, from_tick: int, to_tick: int, name: str, x: int | None = None,
            z: int | None = None, level: int = 0, description: str = "",
            state_vars: str = DEFAULT_STATE_VARS, setup: list[str] = (),
            background_ticks: int = 30, scope: list[int] | None = None,
            seed_bank: bool = False) -> tuple[dict, dict]:
    """Returns (scenario, live) for ticks [from_tick, to_tick] of one transcript."""
    state_re = re.compile(state_vars)
    seeded = SEEDED_INVS + ((95,) if seed_bank else ())
    state: dict[tuple[str, int], int] = {}
    names: dict[tuple[str, int], str] = {}
    stats: dict[str, list[int]] = {}
    invs: dict[int, dict[int, tuple[int, int]]] = {}
    background: set[tuple[str, int]] = set()
    steps: list[dict] = []
    events: list[dict] = []
    unsupported: list[dict] = []
    local_index: int | None = None
    pos: tuple[int, int, int] | None = None
    start_pos: tuple[int, int, int] | None = None
    saw_resume = False
    actor: object = None  # "player", an npc type id, or None while inside another entity's block
    engaged: set[int] = set()
    tick = -1
    inv_ctx: int | None = None
    inv_seq = 0
    header: list[str] = []

    def move(new: tuple[int, int, int]) -> None:
        nonlocal pos
        if new != pos:
            pos = new
            if from_tick <= tick:
                events.append({"tick": tick - from_tick, "kind": "pos", "x": new[0], "z": new[1],
                               "level": new[2]})

    with open(capture, encoding="utf-8", errors="replace") as f:
        for line in f:
            if tick < 0 and not TICK.match(line):
                header.append(line.rstrip())
                if line.startswith("local player index:"):
                    local_index = int(line.split(":")[1])
            t = TICK.match(line)
            if t:
                tick = int(t.group(1))
                inv_ctx = None
                if tick == from_tick:
                    start_pos = pos
                if tick > to_tick:
                    break
                continue
            nested = NESTED.match(line)
            if nested:
                if actor is not None and from_tick <= tick:
                    tag, rest = nested.groups()
                    rel = tick - from_tick
                    if tag == "face_pathingentity" and actor == "player":
                        ref = NPC_REF.search(rest)
                        if ref:
                            engaged.add(int(ref.group(2)))
                    elif tag == "hit":
                        if actor != "player":
                            engaged.add(actor)
                        events.append({"tick": rel, "kind": "hit", "actor": actor,
                                       "type": field_id(rest, "id"),
                                       "value": field_id(rest, "value")})
                    elif tag in ("sequence", "spotanim"):
                        ident = field_id(rest, "id")
                        if ident >= 0:
                            events.append({"tick": rel, "kind": "anim" if tag == "sequence"
                                           else "spotanim", "actor": actor, "id": ident})
                continue
            sub = SUB_EVENT.match(line)
            if sub and sub.group(1) in ("idle", "walk", "run", "teleport", "crawl", "add", "del"):
                body = sub.group(2)
                p = PLAYER.search(body)
                npc = NPC_REF.search(body)
                if p:
                    is_local = local_index is not None and int(p.group(1)) == local_index
                    actor = "player" if is_local else None
                    if is_local:
                        c = NEWCOORD.search(body) or COORD.search(body)
                        if c:
                            move(tuple(int(v) for v in c.groups()))
                elif npc:
                    actor = int(npc.group(2))
                continue
            o = INV_LINE.match(line)
            if o and inv_ctx is not None:
                slot = int(o.group(1)) if o.group(1) is not None else inv_seq
                inv_seq += 1
                obj = int(o.group(2)) if o.group(2) is not None else -1
                count = int(o.group(4))
                if tick < from_tick:
                    invs.setdefault(inv_ctx, {})[slot] = (obj, count)
                else:
                    events.append({"tick": tick - from_tick, "kind": "inv", "inv": inv_ctx,
                                   "slot": slot, "obj": obj, "count": count})
                continue
            e = EVENT.match(line)
            if not e:
                continue
            actor = None
            tag = e.group(1)
            ev = parse_event(tag, e.group(2))
            if ev is None:
                continue
            kind = ev["kind"]
            if kind == "continue":
                saw_resume = True
            if kind == "inv_header":
                inv_ctx, inv_seq = ev["inv"], 0
                if tag == "update_inv_full" and tick < from_tick:
                    invs[inv_ctx] = {}
                continue
            if kind == "rebuild":
                move(ev["coord"])
                continue
            if tick < from_tick:
                if kind in ("varbit", "varp") and state_re.search(ev["name"]):
                    state[(kind, ev["id"])] = ev["new"]
                    names[(kind, ev["id"])] = ev["name"]
                if kind == "stat":
                    # [current level, total xp]. RSProx 1.0.5 decodes update_stat_v2's experience
                    # as a small number after the login burst, so keep the largest (login) total.
                    prev = stats.get(ev["stat"], [0, 0])
                    stats[ev["stat"]] = [ev["level"], max(prev[1], ev["xp"])]
                # Anything already happening in the quiet ticks before the flow (cooldown bars,
                # clocks) is background, not a response to the replayed clicks.
                if tick >= from_tick - background_ticks and kind in ("cs2", "varbit", "varp"):
                    background.add((kind, ev["id"]))
                continue
            rel = tick - from_tick
            if kind == "unsupported":
                unsupported.append({"tick": rel, "tag": ev["tag"], "detail": ev["detail"]})
            elif kind in STEP_KINDS:
                if kind == "button" and scope and int(ev["com"].split(":")[0]) not in scope:
                    continue  # a click on an unrelated interface (bank, side tabs)
                steps.append({"tick": rel, **ev})
            else:
                events.append({"tick": rel, **ev})

    if not saw_resume:
        # Narrow transcripts drop resume_pausebutton: infer it from a chat-modal close with no
        # click that tick.
        button_ticks = {s["tick"] for s in steps}
        for ev in events:
            if ev["kind"] == "if_closesub" and ev["target"] == "162:567" and ev["tick"] not in button_ticks:
                steps.append({"tick": ev["tick"], "kind": "continue", "inferred": True})
    for spec in setup:
        parts = [int(p) for p in spec.split(":")]
        st_tick, iface, comp = parts[0], parts[1], parts[2]
        steps.append({"tick": st_tick, "kind": "button", "com": f"{iface}:{comp}", "name": "setup",
                      "sub": parts[3] if len(parts) > 3 else -1, "obj": -1,
                      "op": parts[4] if len(parts) > 4 else 1})
    steps.sort(key=lambda s: s["tick"])

    start_pos = start_pos or pos
    if start_pos is None and (x is None or z is None):
        raise SystemExit("No player position in this transcript; pass --x/--z or use a full one.")
    sx, sz, sl = start_pos if start_pos else (x, z, level)
    source = {"capture": Path(capture).name, "from_tick": from_tick, "to_tick": to_tick,
              "header": [h for h in header if h.startswith(("version", "world"))]}
    scenario = {
        "id": name,
        "description": description or f"Replay of {source['capture']} ticks {from_tick}-{to_tick}",
        "source": source,
        "start": {"x": sx, "z": sz, "level": sl, "scatter": 0},
        "varbits": {str(i): v for (k, i), v in sorted(state.items()) if k == "varbit"},
        "varps": {str(i): v for (k, i), v in sorted(state.items()) if k == "varp"},
        "var_names": {str(i): names[(k, i)] for (k, i) in sorted(state)},
        "stats": stats,
        "inv_slots": {
            str(inv): [[slot, obj, count] for slot, (obj, count) in sorted(slots.items()) if obj != -1]
            for inv, slots in invs.items() if inv in seeded
        },
        "steps": steps,
        "unsupported": unsupported,
        "duration_ticks": to_tick - from_tick + 1,
        "warmup_ticks": max([5] + [1 - st["tick"] for st in steps if st["tick"] < 0]),
    }
    live = {
        "source": source,
        "background": [{"kind": k, "id": i} for k, i in sorted(background)],
        "scope": sorted(scope) if scope else None,
        # Npc types the player fought or faced: only their combat events are diffed.
        "engaged": sorted(engaged),
        "events": events,
    }
    return scenario, live


def write(scenario: dict, live: dict, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{scenario['id']}.scenario.json").write_text(json.dumps(scenario, indent=2) + "\n")
    (out / f"{scenario['id']}.live.json").write_text(json.dumps(live, indent=2) + "\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--capture", required=True)
    ap.add_argument("--from-tick", type=int, required=True)
    ap.add_argument("--to-tick", type=int, required=True)
    ap.add_argument("--state-vars", default=DEFAULT_STATE_VARS, help="regex of var names to seed")
    ap.add_argument("--name", required=True)
    ap.add_argument("--description", default="")
    ap.add_argument("--x", type=int, help="start x when the transcript has no player position")
    ap.add_argument("--z", type=int)
    ap.add_argument("--level", type=int, default=0)
    ap.add_argument("--background-ticks", type=int, default=30,
                    help="ticks before the flow whose cs2/var ids count as background noise")
    ap.add_argument("--seed-bank", action="store_true", help="also seed the bank (inv 95)")
    ap.add_argument("--out", default=".data/botswarm/scenarios")
    ap.add_argument("--scope", help="comma-separated interface ids that make up the flow; other "
                                    "clicks are not replayed and other interfaces are not diffed")
    ap.add_argument("--setup", action="append", default=[],
                    help="unrecorded setup click before step 0, as tick:iface:comp[:sub[:op]], e.g. "
                         "--setup=-3:629:34 to open the journal's Leagues tab")
    args = ap.parse_args()
    scenario, live = extract(args.capture, args.from_tick, args.to_tick, args.name, args.x, args.z,
                             args.level, args.description, args.state_vars, args.setup,
                             args.background_ticks,
                             [int(x) for x in args.scope.split(",")] if args.scope else None,
                             args.seed_bank)
    write(scenario, live, Path(args.out))
    kinds = {}
    for s in scenario["steps"]:
        kinds[s["kind"]] = kinds.get(s["kind"], 0) + 1
    print(f"{len(scenario['steps'])} steps {kinds}, {len(live['events'])} live events, "
          f"{len(scenario['varbits']) + len(scenario['varps'])} vars, {len(scenario['stats'])} stats, "
          f"{sum(len(v) for v in scenario['inv_slots'].values())} seeded items, start "
          f"{scenario['start']['x']},{scenario['start']['z']},{scenario['start']['level']}, "
          f"{len(scenario['unsupported'])} unsupported inputs -> {args.out}")


if __name__ == "__main__":
    main()
