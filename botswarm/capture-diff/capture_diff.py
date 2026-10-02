"""Diff a bot replay (ours.json) against the live capture window (<scenario>.live.json).

    py -3.12 tools/capture-diff/capture_diff.py \
        --live .data/botswarm/scenarios/relic-unlock-t8.live.json \
        --ours .data/botswarm/replays/relic-unlock-t8-<stamp>/ours.json \
        --out  .data/botswarm/replays/relic-unlock-t8-<stamp>

Events are matched per key (varbit id, interface id, component, cs2 id, inventory slot...) within
--tolerance ticks. Each live event ends up as: match, shifted (right value, different tick),
different (same key, different value), mistimed (same event and value, outside the tolerance),
or missing. Leftover events of ours are extra.

Noise is dropped before matching: clock/cooldown vars (--ignore-vars), explicit cs2 ids
(--ignore-cs2), and every cs2/var id the capture already showed in the quiet ticks before the
flow (the live file's "background", unless --keep-background).

The verdict is FAIL when any missing/different/extra divergence is not in --baseline (a file of
accepted signatures; write one from the current run with --write-baseline). Exit code 0 = PASS,
1 = FAIL. diff.md is the human report, grouped by the replayed step that caused each event;
diff.json is the machine summary used by aggregate.py.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

DEFAULT_IGNORE_VARS = r"^(date_|map_clock$|clock$|busy$|tracking_playtime)|_cooldown$"
DIVERGENT = ("missing", "different", "mistimed", "extra", "end_state")


def norm_events(names: list[str]) -> list[str]:
    out = []
    for n in names:
        k = n.upper().replace("_", "")
        out.append({"IFSCRIPTTRIGGER": "SCRIPTTRIGGER"}.get(k, k))
    return sorted(out)


def key_value(ev: dict) -> tuple[tuple, object, str]:
    """(match key, comparable value, human label)."""
    k = ev["kind"]
    if k in ("varbit", "varp"):
        return (k, ev["id"]), ev["new"], f"{k} {ev.get('name') or ev['id']} ({ev['id']}) = {ev['new']}"
    if k == "if_opensub":
        return (k, ev["iface"]), ev.get("type"), (
            f"open {ev.get('name') or ev['iface']} ({ev['iface']}) as {ev.get('type')}")
    if k == "if_closesub":
        ident = ev["iface"] if ev.get("iface", -1) != -1 else ev.get("target")
        return (k, ident), None, f"close {ev.get('name') or ident} ({ident})"
    if k == "if_setevents":
        val = (ev["start"], ev["end"], tuple(norm_events(ev["events"])))
        return (k, ev["com"]), val, (
            f"setevents {ev.get('name') or ev['com']} ({ev['com']}) {ev['start']}..{ev['end']} "
            f"{'+'.join(norm_events(ev['events']))}")
    if k == "cs2":
        return (k, ev["id"]), ev.get("args", ""), f"cs2 {ev['id']}({ev.get('args', '')})"
    if k == "if_settext":
        return (k, ev["com"]), ev["text"], f"settext {ev.get('name') or ev['com']} = \"{ev['text']}\""
    if k == "if_setobject":
        return (k, ev["com"]), ev["obj"], f"setobject {ev.get('name') or ev['com']} = obj {ev['obj']}"
    if k == "if_sethide":
        return (k, ev["com"]), ev["hidden"], f"sethide {ev.get('name') or ev['com']} = {ev['hidden']}"
    if k == "inv":
        return (k, ev["inv"], ev["slot"]), (ev["obj"], ev["count"]), (
            f"inv {ev['inv']} slot {ev['slot']} = obj {ev['obj']} x{ev['count']}")
    if k == "pos":
        return (k,), (ev["x"], ev["z"], ev["level"]), f"position {ev['x']},{ev['z']},{ev['level']}"
    if k == "stat":
        # Levels only: RSProx decodes update_stat_v2 experience unreliably after login.
        return (k, ev["stat"]), ev["level"], f"stat {ev['stat']} level {ev['level']}"
    if k in ("anim", "spotanim"):
        who = "player" if ev["actor"] == "player" else f"npc {ev['actor']}"
        return (k, str(ev["actor"])), ev["id"], f"{k} {who} = {ev['id']}"
    if k == "hit":
        # Damage rolls are random: compare who was hit and with which hitmark, not the value.
        who = "player" if ev["actor"] == "player" else f"npc {ev['actor']}"
        kind = ev.get("type") if ev["actor"] == "player" else None
        return (k, str(ev["actor"])), kind, f"hit on {who}" + (f" (hitmark {kind})" if kind is not None else "")
    if k == "message":
        return (k, ev["text"]), None, f"message \"{ev['text']}\""
    return (k,), None, k


def shorten(text: str, limit: int = 140) -> str:
    text = text.replace("|", "/")
    return text if len(text) <= limit else text[: limit - 1] + "…"


def iface_of(ev: dict) -> int | None:
    """The interface an event belongs to, or None for events not tied to one (vars, cs2, chat)."""
    if ev["kind"] in ("if_opensub", "if_closesub"):
        if ev.get("iface", -1) != -1:
            return ev["iface"]
        return int(str(ev.get("target", "-1:0")).split(":")[0])
    if ev["kind"] in ("if_setevents", "if_settext", "if_setobject", "if_sethide"):
        return int(str(ev["com"]).split(":")[0])
    return None


def flaky_keys(ours_a: dict, ours_b: dict) -> set[tuple]:
    """Keys whose events differ between two recordings of the SAME scenario on our server: timing
    noise, randomness or world state the replay does not control. They are excluded from the diff
    against live, because no live comparison of them can be trusted."""
    def bag(doc: dict) -> dict[tuple, list]:
        out: dict[tuple, list] = {}
        for ev in doc["events"]:
            k, v, _ = key_value(ev)
            out.setdefault(k, []).append((ev["tick"], json.dumps(v, default=str)))
        return out
    a, b = bag(ours_a), bag(ours_b)
    return {k for k in set(a) | set(b) if sorted(a.get(k, [])) != sorted(b.get(k, []))}


def end_state(live: list[dict], ours: list[dict]) -> list[dict]:
    """Final value of every var live changed, compared with ours: catches state that ends up
    different even when the event streams are hard to line up."""
    def final(events: list[dict]) -> dict[tuple, tuple]:
        out: dict[tuple, tuple] = {}
        for ev in sorted(events, key=lambda e: e["tick"]):
            if ev["kind"] in ("varbit", "varp"):
                out[(ev["kind"], ev["id"])] = (ev["new"], ev.get("name") or ev["id"], ev["tick"])
        return out
    live_final, ours_final = final(live), final(ours)
    rows = []
    for key, (value, name, tick) in sorted(live_final.items()):
        ours_value = ours_final.get(key, (None,))[0]
        if ours_value != value:
            rows.append({
                "tick": tick, "kind": key[0], "key": list(key), "status": "end_state",
                "live": f"{key[0]} {name} ({key[1]}) ends at {value}",
                "ours": f"ends at {ours_value}" if ours_value is not None else "never changed",
            })
    return rows


def diff(live_doc: dict, ours_doc: dict, tolerance: int, ignore_vars: str, ignore_cs2: set[int],
         keep_background: bool, scope: set[int] | None = None,
         flaky: set[tuple] = frozenset()) -> list[dict]:
    var_re = re.compile(ignore_vars)
    scope = scope if scope is not None else set(live_doc.get("scope") or []) or None
    engaged = {str(t) for t in live_doc.get("engaged") or []}
    background = set() if keep_background else {
        (b["kind"], b["id"]) for b in live_doc.get("background", [])}

    def keep(ev: dict) -> bool:
        if ev["kind"] in ("varbit", "varp") and var_re.search(ev.get("name") or ""):
            return False
        if ev["kind"] == "cs2" and ev["id"] in ignore_cs2:
            return False
        if (ev["kind"], ev.get("id")) in background:
            return False
        if flaky and key_value(ev)[0] in flaky:
            return False
        if ev["kind"] in ("anim", "spotanim", "hit") and ev["actor"] != "player" \
                and str(ev["actor"]) not in engaged:
            return False  # npcs the player never engaged on live: their combat is not the flow's
        if scope is not None:
            iface = iface_of(ev)
            if iface is not None and iface not in scope:
                return False
            if ev["kind"] == "inv" and ev["inv"] not in (93, 94):
                return False
            if ev["kind"] == "varp" and ev.get("id") in (262, 263):  # if2/if3: interface state
                return False
        return True

    live = sorted((e for e in live_doc["events"] if keep(e)), key=lambda e: e["tick"])
    ours = [e for e in ours_doc["events"] if keep(e)]
    used = [False] * len(ours)
    results: list[dict] = []
    for ev in live:
        k, v, label = key_value(ev)
        best, best_score = None, None
        for i, o in enumerate(ours):
            if used[i]:
                continue
            ok, ov, _ = key_value(o)
            if ok != k or abs(o["tick"] - ev["tick"]) > tolerance:
                continue
            score = (0 if ov == v else 1, abs(o["tick"] - ev["tick"]))
            if best_score is None or score < best_score:
                best, best_score = i, score
        row = {"tick": ev["tick"], "kind": ev["kind"], "key": list(k), "live": label, "ours": ""}
        if best is None:
            row["status"] = "missing"
        else:
            used[best] = True
            o = ours[best]
            row["ours"], row["ours_tick"] = key_value(o)[2], o["tick"]
            row["status"] = ("different" if best_score[0] else
                             "shifted" if best_score[1] else "match")
        results.append(row)
    # Second pass: the same event with the same value, outside the tolerance, is one mistimed
    # event (e.g. an item granted 5 ticks earlier than live), not a missing + an extra.
    for row in results:
        if row["status"] != "missing":
            continue
        for i, o in enumerate(ours):
            if used[i]:
                continue
            ok, ov, olabel = key_value(o)
            if list(ok) == row["key"] and olabel == row["live"]:
                used[i] = True
                dt = o["tick"] - row["tick"]
                row.update(status="mistimed", ours=f"{olabel} ({'+' if dt > 0 else ''}{dt} ticks)",
                           ours_tick=o["tick"])
                break
    for i, o in enumerate(ours):
        if not used[i]:
            k, _, label = key_value(o)
            results.append({"tick": o["tick"], "kind": o["kind"], "key": list(k), "live": "",
                            "ours": label, "status": "extra"})
    results.extend(end_state(live, ours))
    for r in results:
        text = r["live"] or r["ours"]
        r["signature"] = f"{r['status']}|{text}"
        r["shape"] = f"{r['status']}|{'/'.join(str(x) for x in r['key'])}"
    return results


def step_of(tick: int, steps: list[dict]) -> int:
    """Index of the last replayed step at or before tick (-1 = before the first step)."""
    idx = -1
    for i, s in enumerate(steps):
        if s["tick"] <= tick:
            idx = i
    return idx


def report(live_doc: dict, ours_doc: dict, results: list[dict], baseline: set[str],
           strict_timing: bool, tolerance: int) -> tuple[str, dict]:
    counts = {s: sum(1 for r in results if r["status"] == s)
              for s in ("match", "shifted", "different", "mistimed", "missing", "extra",
                        "end_state")}
    failing = DIVERGENT + (("shifted",) if strict_timing else ())
    new = [r for r in results if r["status"] in failing and r["signature"] not in baseline]
    accepted = [r for r in results if r["status"] in failing and r["signature"] in baseline]
    verdict = "PASS" if not new else "FAIL"
    steps = [s for s in ours_doc.get("steps", []) if s["tick"] >= 0]

    lines = [
        f"# Capture diff: {ours_doc.get('scenario', '?')} — {verdict}",
        "",
        f"Live `{live_doc.get('source', {}).get('capture', '?')}` "
        f"ticks {live_doc.get('source', {}).get('from_tick', '?')}-"
        f"{live_doc.get('source', {}).get('to_tick', '?')} · tolerance ±{tolerance} ticks · "
        f"{len(live_doc.get('background', []))} background ids ignored"
        + (f" · scope: interfaces {', '.join(str(i) for i in live_doc['scope'])}"
           if live_doc.get("scope") else ""),
        "",
        "| match | shifted | different | mistimed | missing (live only) | extra (ours only) "
        "| end state | new vs baseline |",
        "|---|---|---|---|---|---|---|---|",
        f"| {counts['match']} | {counts['shifted']} | {counts['different']} | {counts['mistimed']} "
        f"| {counts['missing']} | {counts['extra']} | {counts['end_state']} | {len(new)} |",
    ]
    setup = [s for s in ours_doc.get("steps", []) if s["tick"] < 0]
    if setup:
        lines += ["", "Setup: " + "; ".join(f"t{s['tick']} {s.get('note')}" for s in setup)]

    marks = {"missing": "live only", "extra": "ours only", "different": "differs",
             "mistimed": "mistimed", "shifted": "shifted", "match": "match",
             "end_state": "end state"}
    groups: dict[int, list[dict]] = {}
    for r in results:
        groups.setdefault(step_of(r["tick"], steps), []).append(r)
    for idx in sorted(groups):
        rows = sorted(groups[idx], key=lambda r: (r["tick"], r["status"]))
        title = ("Before the first step" if idx < 0 else
                 f"t{steps[idx]['tick']} {steps[idx]['kind']} {steps[idx].get('com') or ''} — "
                 f"{steps[idx].get('note')}")
        matched = sum(1 for r in rows if r["status"] == "match")
        lines += ["", f"## {title}", "", f"{matched} event(s) match live."]
        divergent = [r for r in rows if r["status"] != "match"]
        if not divergent:
            continue
        lines += ["", "| tick | | live | ours | |", "|---|---|---|---|---|"]
        folded: dict[tuple, list[int]] = {}
        for r in divergent:
            folded.setdefault((r["status"], r["live"], r["ours"], r["signature"] in baseline), []).append(r["tick"])
        for (status, live, ours, known), ticks in sorted(folded.items(), key=lambda kv: kv[1][0]):
            when = str(ticks[0]) if len(ticks) == 1 else f"{ticks[0]}-{ticks[-1]} (x{len(ticks)})"
            lines.append(f"| {when} | {marks[status]} | {shorten(live)} | {shorten(ours)} | "
                         f"{'known' if known else '**new**' if status in failing else ''} |")
    summary = {
        "scenario": ours_doc.get("scenario"),
        "source": live_doc.get("source"),
        "verdict": verdict,
        "counts": counts,
        "new": len(new),
        "accepted": len(accepted),
        "divergences": [
            {k: r[k] for k in ("status", "tick", "kind", "live", "ours", "signature", "shape")}
            for r in results if r["status"] != "match"
        ],
    }
    return "\n".join(lines) + "\n", summary


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--live", required=True)
    ap.add_argument("--ours", required=True)
    ap.add_argument("--out", help="directory for diff.md + diff.json (default: next to --ours)")
    ap.add_argument("--tolerance", type=int, default=2)
    ap.add_argument("--ignore-vars", default=DEFAULT_IGNORE_VARS)
    ap.add_argument("--ignore-cs2", default="", help="comma-separated cs2 ids to ignore")
    ap.add_argument("--keep-background", action="store_true",
                    help="do not drop ids the capture showed before the flow")
    ap.add_argument("--baseline", help="JSON file of accepted divergence signatures")
    ap.add_argument("--write-baseline", help="write this run's divergences as an accepted baseline")
    ap.add_argument("--strict-timing", action="store_true", help="shifted events also fail")
    ap.add_argument("--ours2", help="a second recording of the same scenario: keys that differ "
                                    "between the two are flaky and excluded")
    ap.add_argument("--scope", help="comma-separated interface ids to compare (default: the live "
                                    "file's scope, if any)")
    args = ap.parse_args()

    live_doc = json.loads(Path(args.live).read_text(encoding="utf-8"))
    ours_doc = json.loads(Path(args.ours).read_text(encoding="utf-8"))
    ignore_cs2 = {int(x) for x in args.ignore_cs2.split(",") if x.strip()}
    scope = {int(x) for x in args.scope.split(",")} if args.scope else None
    flaky: set[tuple] = set()
    if args.ours2:
        flaky = flaky_keys(ours_doc, json.loads(Path(args.ours2).read_text(encoding="utf-8")))
    results = diff(live_doc, ours_doc, args.tolerance, args.ignore_vars, ignore_cs2,
                   args.keep_background, scope, flaky)
    baseline: set[str] = set()
    if args.baseline and Path(args.baseline).is_file():
        baseline = set(json.loads(Path(args.baseline).read_text(encoding="utf-8"))["accepted"])
    text, summary = report(live_doc, ours_doc, results, baseline, args.strict_timing, args.tolerance)
    if flaky:
        text += ("\n## Flaky on our server (excluded)\n\n" + "\n".join(
            f"- `{'/'.join(str(x) for x in k)}`" for k in sorted(flaky, key=str)) + "\n")
        summary["flaky"] = sorted("/".join(str(x) for x in k) for k in flaky)

    out = Path(args.out) if args.out else Path(args.ours).parent
    out.mkdir(parents=True, exist_ok=True)
    (out / "diff.md").write_text(text, encoding="utf-8")
    (out / "diff.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    if args.write_baseline:
        accepted = sorted({d["signature"] for d in summary["divergences"] if d["status"] in DIVERGENT})
        Path(args.write_baseline).write_text(
            json.dumps({"scenario": summary["scenario"], "accepted": accepted}, indent=2) + "\n",
            encoding="utf-8")
    print(text)
    raise SystemExit(0 if summary["verdict"] == "PASS" else 1)


if __name__ == "__main__":
    main()
