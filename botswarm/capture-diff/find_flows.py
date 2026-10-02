"""Find every occurrence of a flow across the transcribed captures, and optionally extract each one.

    py -3.12 tools/capture-diff/find_flows.py --anchor "league_relics:confirm_button" \
        --opener "league_side_panel:relics_button" --after 15 \
        --name relic-confirm --x 3222 --z 3218 --setup=-3:629:34 --extract

One live example can be incidental; the same divergence in every example is behaviour. Extracting
all occurrences lets replay.sh run them as a set and aggregate.py report how many show each
divergence.

The window for each occurrence starts at the latest --opener click within --lookback ticks before
the --anchor click (so the replay opens the interface itself), or --before ticks before the anchor
when no opener is found, and ends --after ticks after the anchor. Occurrences whose window would
overlap the previous one in the same capture are merged into it.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from capture_extract import DEFAULT_STATE_VARS, TICK, extract, write
from transcribe_full import transcribe

BUTTON = re.compile(r"^\s{4}\[if_buttonx\] com=([^\s(]+) ")


def scan(path: Path, anchor: re.Pattern, opener: re.Pattern | None) -> tuple[list[int], list[int], str]:
    anchors, openers, version, tick = [], [], "", -1
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("version:"):
                version = line.split(":", 1)[1].strip()
            t = TICK.match(line)
            if t:
                tick = int(t.group(1))
                continue
            b = BUTTON.match(line)
            if not b:
                continue
            if anchor.search(b.group(1)):
                anchors.append(tick)
            elif opener and opener.search(b.group(1)):
                openers.append(tick)
    return anchors, openers, version


def top_components(captures: Path, n: int) -> None:
    """Most-clicked interface components across every transcript: what players actually use."""
    clicks: dict[str, int] = {}
    files: dict[str, set[str]] = {}
    for path in sorted(captures.glob("*.txt")):
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                b = BUTTON.match(line)
                if b:
                    clicks[b.group(1)] = clicks.get(b.group(1), 0) + 1
                    files.setdefault(b.group(1), set()).add(path.name)
    print(f"| clicks | captures | component |")
    print("|---|---|---|")
    for com, count in sorted(clicks.items(), key=lambda kv: -kv[1])[:n]:
        print(f"| {count} | {len(files[com])} | {com} |")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--captures", default="E:/DevGarden/RelicScape/kris-leagues-stuff")
    ap.add_argument("--anchor", help="regex on the if_buttonx component name")
    ap.add_argument("--top", type=int, help="list the N most-clicked components across all "
                                           "captures (to pick flows to test) and exit")
    ap.add_argument("--opener", help="regex on the click that opens the flow (window start)")
    ap.add_argument("--lookback", type=int, default=120)
    ap.add_argument("--before", type=int, default=10)
    ap.add_argument("--after", type=int, default=15)
    ap.add_argument("--extract", action="store_true")
    ap.add_argument("--per-capture", type=int, default=0,
                    help="keep at most N occurrences per capture (spread a common flow out)")
    ap.add_argument("--limit", type=int, default=0, help="keep at most N occurrences in total")
    ap.add_argument("--full", action="store_true",
                    help="extract from full transcripts (made on demand by transcribe_full.py): "
                         "adds start position, levels, exact Continue and world clicks, chat")
    ap.add_argument("--seed-bank", action="store_true")
    ap.add_argument("--name", default="flow")
    ap.add_argument("--x", type=int, default=3222, help="start x when the transcript has no position")
    ap.add_argument("--z", type=int, default=3218)
    ap.add_argument("--level", type=int, default=0)
    ap.add_argument("--state-vars", default=DEFAULT_STATE_VARS)
    ap.add_argument("--setup", action="append", default=[])
    ap.add_argument("--scope", help="comma-separated interface ids that make up the flow")
    ap.add_argument("--out", default=".data/botswarm/scenarios")
    args = ap.parse_args()

    if args.top:
        top_components(Path(args.captures), args.top)
        return
    if not args.anchor:
        ap.error("--anchor or --top is required")
    anchor = re.compile(args.anchor)
    opener = re.compile(args.opener) if args.opener else None
    windows: list[tuple[Path, str, int, int, int]] = []
    for path in sorted(Path(args.captures).glob("*.txt")):
        anchors, openers, version = scan(path, anchor, opener)
        last_end = -1
        for a in anchors:
            prior = [o for o in openers if a - args.lookback <= o <= a]
            start = max(prior) if prior else a - args.before
            end = a + args.after
            if start <= last_end:
                path_, ver, s0, _, n = windows[-1]
                windows[-1] = (path_, ver, s0, end, n + 1)
            else:
                windows.append((path, version, start, end, 1))
            last_end = end

    if args.per_capture:
        kept, per = [], {}
        for w in windows:
            per[w[0]] = per.get(w[0], 0) + 1
            if per[w[0]] <= args.per_capture:
                kept.append(w)
        windows = kept
    if args.limit:
        windows = windows[: args.limit]
    print(f"{len(windows)} occurrence(s) of /{args.anchor}/ in {args.captures}")
    print("| # | capture | version | ticks | anchors | opener |")
    print("|---|---|---|---|---|---|")
    ids = []
    for i, (path, version, start, end, n) in enumerate(windows, 1):
        print(f"| {i} | {path.name} | {version} | {start}-{end} | {n} | "
              f"{'yes' if opener else '-'} |")
        if args.extract:
            sid = f"{args.name}-{i:02d}"
            source = transcribe(path.stem) if args.full else path
            scenario, live = extract(str(source), start, end, sid, args.x, args.z, args.level,
                                     f"{args.name} occurrence {i}: {path.name} ticks {start}-{end}",
                                     args.state_vars, args.setup, 30,
                                     [int(x) for x in args.scope.split(",")] if args.scope else None,
                                     args.seed_bank)
            write(scenario, live, Path(args.out))
            ids.append(sid)
    if args.extract:
        manifest = Path(args.out) / f"{args.name}.manifest.json"
        manifest.write_text(json.dumps({"flow": args.name, "anchor": args.anchor,
                                        "scenarios": ids}, indent=2) + "\n")
        print(f"\nExtracted {len(ids)} scenario(s); manifest {manifest}")


if __name__ == "__main__":
    main()
