# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-22, reviewed by Dongkun Moon
"""Line (C0) and branch (C1) coverage per Android layer, from a JaCoCo XML report.

    python scripts/coverage_by_layer.py app/build/reports/coverage/test/debug/report.xml
    python scripts/coverage_by_layer.py app/build/reports/coverage/androidTest/debug/connected/report.xml
    python scripts/coverage_by_layer.py app/build/intermediates/aggregated_code_coverage_data/global/collectDebugAggregatedCoverage/debugAppAggregatedXmlReport.xml

Run from frontend/ after ./gradlew createDebugUnitTestCoverageReport (JVM unit tests),
-PandroidTestCoverage createDebugAndroidTestCoverageReport (device tests) or
-PandroidTestCoverage createAggregatedCoverageReport (both, merged). Layers follow the wiki Testing Documentation:
  data       com.swpp.stylemate.data.*  (repositories, stores, models)
  ViewModel  classes whose name contains "ViewModel"
  UI         everything else under com.swpp.stylemate.ui and MainActivity
Generated classes (BuildConfig, R, ComposableSingletons) are left out.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

ROOT = "com/swpp/stylemate/"

def layer(class_name: str) -> str | None:
    if not class_name.startswith(ROOT):
        return None
    rest = class_name[len(ROOT):]
    simple = rest.rsplit("/", 1)[-1]
    if (
        simple in ("BuildConfig", "R")
        or simple.startswith(("BuildConfig$", "R$", "ComposableSingletons"))
    ):
    return None
    if rest.startswith("data/"):
        return "data"
    if "ViewModel" in simple.split("$", 1)[0]:
        return "ViewModel"
    return "UI"


def pct(covered: int, total: int) -> str:
    return f"{100 * covered / total:.1f} %" if total else "n/a"


def main() -> None:
    tree = ET.parse(sys.argv[1])
    sums = {name: {"LINE": [0, 0], "BRANCH": [0, 0]} for name in ("data", "ViewModel", "UI")}
    for cls in tree.getroot().iter("class"):
        name = layer(cls.get("name", ""))
        if name is None:
            continue
        for counter in cls.findall("counter"):
            kind = counter.get("type")
            if kind in ("LINE", "BRANCH"):
                sums[name][kind][0] += int(counter.get("covered"))
                sums[name][kind][1] += int(counter.get("covered")) + int(counter.get("missed"))
    print("| Layer | Lines (C0) | Branches (C1) |")
    print("| --- | --- | --- |")
    for name, c in sums.items():
        (lc, lt), (bc, bt) = c["LINE"], c["BRANCH"]
        print(f"| {name} | {pct(lc, lt)} ({lc}/{lt}) | {pct(bc, bt)} ({bc}/{bt}) |")


if __name__ == "__main__":
    main()
