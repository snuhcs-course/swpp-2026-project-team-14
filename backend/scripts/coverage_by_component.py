# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-22, reviewed by Dongkun Moon
"""Statement (C0) and branch (C1) coverage per backend component, from coverage.py's JSON report.

    python -m coverage json -o coverage.json
    python scripts/coverage_by_component.py coverage.json

The components match the rows of the wiki Testing Documentation. Files outside every component
(e.g. config/) are listed under "other". What is measured at all is set in .coveragerc.
"""

from __future__ import annotations

import json
import sys

COMPONENTS = {
    "Authentication, profile and import (apps/users)": ("apps/users/",),
    "Wardrobe and outfit APIs (apps/wardrobe, apps/outfits)": ("apps/wardrobe/", "apps/outfits/"),
    "Recommendation logic (recommendation)": ("recommendation/",),
    "Body analysis (body_analysis, apps/body_profiles)": ("body_analysis/", "apps/body_profiles/"),
}


def pct(covered: int, total: int) -> str:
    return f"{100 * covered / total:.1f} %" if total else "n/a"


def main() -> None:
    data = json.load(open(sys.argv[1] if len(sys.argv) > 1 else "coverage.json", encoding="utf-8"))
    sums: dict[str, list[int]] = {name: [0, 0, 0, 0] for name in [*COMPONENTS, "other"]}
    for path, info in data["files"].items():
        path = path.replace("\\", "/")
        name = next((n for n, prefixes in COMPONENTS.items() if path.startswith(prefixes)), "other")
        s = info["summary"]
        for i, key in enumerate(("covered_lines", "num_statements", "covered_branches", "num_branches")):
            sums[name][i] += s[key]
    print("| Component | Statements (C0) | Branches (C1) |")
    print("| --- | --- | --- |")
    for name, (cl, ns, cb, nb) in sums.items():
        if ns:
            print(f"| {name} | {pct(cl, ns)} ({cl}/{ns}) | {pct(cb, nb)} ({cb}/{nb}) |")


if __name__ == "__main__":
    main()
