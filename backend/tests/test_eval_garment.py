"""Scoring logic of scripts/eval_garment.py (no model calls)."""

import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("eval_garment", ROOT / "scripts" / "eval_garment.py")
eg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(eg)

CASE = {"expect": {"category": "top", "subcategory": ["shirt", "blouse"]}}


def row(status="ok", category=True, subcategory=True):
    return {"status": status, "correct": {"category": category, "subcategory": subcategory}}


def test_score_accepts_any_listed_subcategory():
    assert eg.score(CASE, {"category": "top", "subcategory": "blouse"}) == {"category": True, "subcategory": True}
    assert eg.score(CASE, {"category": "top", "subcategory": "tshirt"}) == {"category": True, "subcategory": False}
    assert eg.score(CASE, {"category": "outerwear", "subcategory": "shirt"}) == {"category": False, "subcategory": True}


def test_format_error_counts_as_wrong():
    assert eg.score(CASE, None) == {"category": False, "subcategory": False}


def test_pass_needs_every_threshold():
    rows = [row()] * 19 + [row(subcategory=False)]  # 100 % category, 95 % subcategory
    assert eg.summarize(rows)["verdict"] == "PASS"
    rows = [row()] * 16 + [row(subcategory=False)] * 4  # 80 % subcategory
    assert eg.summarize(rows)["verdict"] == "FAIL"
    rows = [row()] * 19 + [row("format_error", False, False)]  # one format error fails even at 95 %
    summary = eg.summarize(rows)
    assert summary["format_errors"] == 1 and summary["verdict"] == "FAIL"


def test_unresolved_infra_error_is_excluded_and_incomplete():
    rows = [row()] * 10 + [row("infra_error", False, False)]
    summary = eg.summarize(rows)
    assert summary["scored_cases"] == 10 and summary["category_accuracy"] == 1.0
    assert summary["verdict"] == "INCOMPLETE"


def test_fixed_cases_match_the_images():
    doc = json.loads((eg.EVAL_DIR / "cases.json").read_text(encoding="utf-8"))
    cases = doc["cases"]
    assert len(cases) >= 30
    assert len({c["id"] for c in cases}) == len(cases)
    for c in cases:
        assert (eg.EVAL_DIR / c["file"]).is_file()
        assert c["expect"]["category"] in {"top", "bottom", "outerwear"}
        assert c["expect"]["subcategory"]
