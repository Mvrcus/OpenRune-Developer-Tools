"""Decision sidecar for the bot swarm (content/other/botswarm, decider "laya").

POST /decide  {"bot", "goal", "context", "options": [label...], "priors": [float...]}
          ->  {"probabilities": [float...], "model": "..."}

Wraps Laya (Apache-2.0, https://github.com/NandhaKishorM/laya) as a typed "choice" question.
Laya is weak zero-shot (0.362 on its own typed-decisions benchmark) and the game's options are not
what it was trained on, so its distribution is BLENDED with the catalog priors (LAYA_BLEND, default
0.5). Run logs from report.json are the fine-tuning data that should raise that blend over time.

Without Laya installed the server still answers, from the priors, so the pipeline can be tested
end to end. Standard library only, apart from the optional `laya` package.

    py -3.12 -m pip install laya        # optional, ~800 MB checkpoint on first use
    py -3.12 tools/bot-decider/server.py --port 7790
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BLEND = float(os.environ.get("LAYA_BLEND", "0.5"))

try:
    from laya import Router  # type: ignore

    ROUTER = Router()
    MODEL = "laya"
except Exception as error:  # pragma: no cover - optional dependency
    ROUTER = None
    MODEL = f"priors-only ({type(error).__name__}: {error})"


def normalise(weights: list[float]) -> list[float]:
    clean = [w if w > 0 else 0.0 for w in weights]
    total = sum(clean)
    if total <= 0:
        return [1.0 / len(clean)] * len(clean)
    return [w / total for w in clean]


def laya_distribution(goal: str, context: str, options: list[str]) -> list[float] | None:
    """One Laya choice question; returns a distribution over `options` or None."""
    if ROUTER is None:
        return None
    keys = [f"o{i}" for i in range(len(options))]
    question = {
        "next_action": {
            "type": "choice",
            "instructions": f"Which action best advances this goal: {goal}?",
            "criteria": dict(zip(keys, options)),
        }
    }
    answer = ROUTER.predict(context, question)["answers"]["next_action"]
    # UNVERIFIED: the README documents answer["choice"]; the per-option distribution's key is not
    # documented, so accept the plausible names and fall back to one-hot on the choice.
    for name in ("probabilities", "distribution", "probs", "scores"):
        dist = answer.get(name)
        if isinstance(dist, dict):
            return normalise([float(dist.get(k, 0.0)) for k in keys])
        if isinstance(dist, list) and len(dist) == len(keys):
            return normalise([float(p) for p in dist])
    choice = answer.get("choice")
    return [1.0 if k == choice else 0.0 for k in keys] if choice in keys else None


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802 - http.server API
        if self.path != "/decide":
            self.send_error(404)
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        options = body["options"]
        priors = normalise([float(p) for p in body.get("priors", [1.0] * len(options))])
        started = time.perf_counter()
        try:
            model = laya_distribution(body.get("goal", ""), body.get("context", ""), options)
        except Exception as error:  # a bad answer must never stall a bot
            print(f"laya error: {error}", file=sys.stderr)
            model = None
        probabilities = (
            priors
            if model is None
            else normalise([BLEND * m + (1 - BLEND) * p for m, p in zip(model, priors)])
        )
        payload = json.dumps(
            {
                "probabilities": probabilities,
                "model": MODEL if model is not None else "priors",
                "ms": round((time.perf_counter() - started) * 1000, 1),
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_: object) -> None:
        pass


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=7790)
    args = parser.parse_args()
    print(f"bot-decider on 127.0.0.1:{args.port} using {MODEL}, blend {BLEND}")
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
