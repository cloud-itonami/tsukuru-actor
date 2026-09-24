#!/usr/bin/env python3
"""Decision-free local measurements for the tsukuru-scout bot.

Reads candidates.edn (the SSoT) and reports honest numbers so the
prompt-level model never invents state.  Mirrors the hermes-hyakka /
hermes-magnesium-systems evidence pattern.
"""
from __future__ import annotations

import datetime as dt
import re
import subprocess
from pathlib import Path

READ_ROOT = Path("~/github/com-junkawasaki").expanduser()
CANDIDATES = READ_ROOT / "orgs/cloud-itonami/tsukuru-actor/kotoba/candidates.edn"

REQUIRED_TOKENS = [
    ":factory/did", ":factory/display-name", ":factory/country",
    ":factory/isic", ":factory/capabilities", ":factory/source-url",
    ":factory/labor-provenance", ":factory/sourcing",
]


def main() -> int:
    print("TSUKURU_EVIDENCE_V1")
    print(f"measured_at={dt.datetime.now(dt.timezone.utc).isoformat()}")
    if not CANDIDATES.is_file():
        print("CANDIDATES present=no")
        return 0
    body = CANDIDATES.read_text(errors="replace")
    dids = re.findall(r":factory/did \"candidate:manufacturer-directory/([a-z]{2})/", body)
    countries = sorted(set(dids))
    isics = sorted(set(re.findall(r":factory/isic \"(C\d\d)\"", body)))
    tokens = ",".join(f"{t[1:].split('/')[-1]}={'yes' if t in body else 'no'}" for t in REQUIRED_TOKENS)
    head = subprocess.run(
        ["git", "rev-parse", "--short=12", "HEAD"], cwd=READ_ROOT,
        text=True, stdout=subprocess.PIPE, timeout=15,
    ).stdout.strip()
    print(f"CANDIDATES present=yes head={head} entries={len(dids)} "
          f"countries={len(countries)} isic_divisions={len(isics)}")
    print(f"REQUIRED_FIELD_TOKENS [{tokens}]")
    print(f"BATCH_COMMENT last={body.rsplit(';; Running total:', 1)[-1].splitlines()[0].strip() if ';; Running total:' in body else 'none'}")
    # duplicate check
    all_dids = re.findall(r":factory/did \"([^\"]+)\"", body)
    dupes = [d for d in set(all_dids) if all_dids.count(d) > 1]
    print(f"DUPLICATE_DIDS count={len(set(dupes))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
