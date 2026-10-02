"""Combine diff.json files from several replays of the same flow into one consensus report.

    py -3.12 tools/capture-diff/aggregate.py --out .data/botswarm/replays/relic-confirm.md \
        .data/botswarm/replays/relic-confirm-01-*/diff.json .data/botswarm/replays/relic-confirm-02-*/diff.json

Divergences are grouped by shape (status + event kind + key, ignoring values that naturally differ
between examples, such as which relic was picked) and ranked by how many runs show them. A shape
seen in every run is consistent behaviour worth fixing; one seen once is likely incidental to that
capture.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("diffs", nargs="+", help="diff.json files")
    ap.add_argument("--out")
    ap.add_argument("--min-share", type=float, default=0.5,
                    help="list shapes seen in at least this share of runs (others are counted)")
    args = ap.parse_args()

    runs = [json.loads(Path(p).read_text(encoding="utf-8")) for p in args.diffs]
    n = len(runs)
    shapes: dict[str, dict] = {}
    for run in runs:
        seen = set()
        for d in run["divergences"]:
            shape = d["shape"]
            if d["kind"] == "inv":  # fold slots: "missing|inv/95/12" -> "missing|inv/95"
                shape = "/".join(shape.split("/")[:2])
            if d["status"] == "shifted" or shape in seen:
                continue
            seen.add(shape)
            entry = shapes.setdefault(shape, {"count": 0, "example": d, "scenarios": []})
            entry["count"] += 1
            entry["scenarios"].append(run["scenario"])

    verdicts = {v: sum(1 for r in runs if r["verdict"] == v) for v in ("PASS", "FAIL")}
    lines = [
        f"# Consensus over {n} replay(s)",
        "",
        f"{verdicts['PASS']} PASS, {verdicts['FAIL']} FAIL. Scenarios: "
        + ", ".join(f"{r['scenario']} ({r['verdict']})" for r in runs),
        "",
        "| seen in | status | example live | example ours |",
        "|---|---|---|---|",
    ]
    rare = 0
    for shape, e in sorted(shapes.items(), key=lambda kv: (-kv[1]["count"], kv[0])):
        if e["count"] < args.min_share * n:
            rare += 1
            continue
        ex = e["example"]
        mark = "**all**" if e["count"] == n else f"{e['count']}/{n}"
        lines.append(f"| {mark} | {ex['status']} | {ex['live'].replace('|', '/')[:120]} | "
                     f"{ex['ours'].replace('|', '/')[:120]} |")
    if rare:
        lines += ["", f"{rare} rarer divergence shape(s) below {args.min_share:.0%} of runs omitted "
                      "(--min-share 0 lists them)."]
    text = "\n".join(lines) + "\n"
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
