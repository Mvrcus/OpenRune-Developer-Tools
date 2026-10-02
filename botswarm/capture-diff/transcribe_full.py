"""Make a FULL transcript of a Leagues capture for replay extraction, without touching the shared one.

    py -3.12 tools/capture-diff/transcribe_full.py 20260420T202435-d8f0250

The shared transcripts in kris-leagues-stuff were made with a narrow RSProx filter preset that
drops player_info, rebuild, update_stat, resume_pausebutton, move/op clicks and message_game, all
of which replays need (start position, levels, exact Continue clicks, world clicks, chat). This
copies the .bin into .data/botswarm/captures/ and transcribes it there with a private RSProx home
(.data/botswarm/rsprox-home) whose only preset enables every packet, including NPC info, hits and
projectiles for combat comparison. Your own RSProx settings and presets are never read or changed;
the private home links to your RSProx cache directory so historic caches are not re-downloaded.
Tick numbers match the shared transcript.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

CAPTURES = Path("E:/DevGarden/RelicScape/kris-leagues-stuff")
OUT = Path(__file__).resolve().parents[2] / ".data/botswarm/captures"
RSPROX = Path.home() / ".rsprox"
HOME = Path(__file__).resolve().parents[2] / ".data/botswarm/rsprox-home"
JAVA = Path.home() / "AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin/java.exe"
REQUIRED = ("PLAYER_INFO", "REBUILD", "UPDATE_STAT", "RESUME_PAUSEBUTTON", "MESSAGE_GAME")


def private_home() -> Path:
    """A private RSProx home whose single preset enables every packet the user's presets know."""
    home = HOME / ".rsprox"
    (home / "filters").mkdir(parents=True, exist_ok=True)
    keys: list[str] = []
    for preset in sorted((RSPROX / "filters").glob("filters-*.txt")):
        for line in preset.read_text().splitlines():
            key = line.split("=", 1)[0]
            if "=" in line and key not in ("version", "creationtime", "name") and key not in keys:
                keys.append(key)
    (home / "filters" / "filters-1700000000000.txt").write_text(
        "\n".join(["version=0", "creationtime=1700000000000", "name=botswarm-everything"]
                  + [f"{k}=true" for k in keys]) + "\n")
    props = [l for l in (RSPROX / "proxy.properties").read_text().splitlines()
             if not l.startswith("filters.status=")]
    (home / "proxy.properties").write_text("\n".join(props + ["filters.status=0"]) + "\n")
    shutil.copy2(RSPROX / "log4j2-quiet.xml", home / "log4j2-quiet.xml")
    caches = home / "caches"
    if not caches.exists():  # a junction, not a copy: ~300 MB of historic caches
        subprocess.run(["cmd", "/c", "mklink", "/J", str(caches), str(RSPROX / "caches")],
                       check=True, capture_output=True)
    return HOME


def active_preset() -> tuple[str, dict[str, str]]:
    """RSProx stores presets as filters-<time>.txt and the active one as an index (filters.status)."""
    props = dict(line.split("=", 1) for line in (RSPROX / "proxy.properties").read_text().splitlines()
                 if "=" in line and not line.startswith("#"))
    index = int(props.get("filters.status", "0"))
    files = sorted((RSPROX / "filters").glob("filters-*.txt"))
    entries = dict(line.split("=", 1) for line in files[index].read_text().splitlines() if "=" in line)
    return entries.get("name", files[index].name), entries


def transcribe(stem: str, force: bool = False) -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    txt = OUT / f"{stem}.txt"
    marker = OUT / f"{stem}.everything"  # made with the all-packets preset (older ones were not)
    if txt.exists() and txt.stat().st_size > 0 and marker.exists() and not force:
        return txt
    home = private_home()
    shutil.copy2(CAPTURES / f"{stem}.bin", OUT / f"{stem}.bin")
    subprocess.run(
        [str(JAVA), f"-Duser.home={home}",
         f"-Dlog4j2.configurationFile=file:///{(home / '.rsprox/log4j2-quiet.xml').as_posix()}",
         "-cp", f"{(RSPROX / 'launcher/repository').as_posix()}/*",
         "net.rsprox.proxy.cli.TranscribeCommandKt", f"-name={(OUT / f'{stem}.bin').resolve().as_posix()}"],
        check=False, capture_output=True)
    if not txt.exists() or txt.stat().st_size == 0:
        sys.exit(f"Transcription of {stem} produced no output.")
    (OUT / f"{stem}.bin").unlink(missing_ok=True)
    marker.touch()
    return txt


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("captures", nargs="+", help="capture stems, e.g. 20260420T202435-d8f0250")
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()
    for stem in args.captures:
        stem = Path(stem).stem
        print(transcribe(stem, args.force))


if __name__ == "__main__":
    main()
