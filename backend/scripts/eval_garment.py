"""옷 분석 AI 평가: python scripts/eval_garment.py

eval/garment/cases.json의 고정 케이스를 실제 분석(apps/wardrobe/analysis.py의 analyze_image)으로 실행하고,
eval/garment/RUBRIC.md의 기준으로 자동 채점한다. 결과는 eval/garment/results/에 JSON으로 저장한다.
API 키와 모델은 서비스와 같은 설정(OPENROUTER_API_KEY, OPENROUTER_VISION_MODEL)에서 읽는다 (서버·DB 불필요).
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
EVAL_DIR = ROOT / "eval" / "garment"

CATEGORY_MIN = 0.95  # RUBRIC.md: 대분류 정확도 95% 이상
SUBCATEGORY_MIN = 0.85  # RUBRIC.md: 종류 정확도 85% 이상
RETRIES = 2  # RUBRIC.md: 인프라 오류는 최대 2번 다시 시도
FORMAT_ERRORS = {"AI_INVALID_RESPONSE"}  # 응답이 형식 검증을 통과하지 못함
INFRA_ERRORS = {"AI_TIMEOUT", "AI_UNAVAILABLE", "AI_PROVIDER_ERROR", "AI_RATE_LIMITED"}  # 다시 시도
SETUP_ERRORS = {"AI_AUTH_FAILED", "AI_CREDITS_REQUIRED", "AI_MODEL_UNAVAILABLE", "AI_REQUEST_INVALID", "AI_NOT_CONFIGURED"}


def score(case: dict, attributes: dict | None) -> dict:
    """케이스 하나의 채점 결과. attributes가 None이면 형식 오류다."""
    expect = case["expect"]
    if attributes is None:
        return {"category": False, "subcategory": False}
    return {
        "category": attributes.get("category") == expect["category"],
        "subcategory": attributes.get("subcategory") in expect["subcategory"],
    }


def summarize(rows: list[dict]) -> dict:
    """지표와 판정 (RUBRIC.md '통과 기준')."""
    scored = [r for r in rows if r["status"] != "infra_error"]
    n = len(scored)
    category = sum(r["correct"]["category"] for r in scored) / n if n else 0.0
    subcategory = sum(r["correct"]["subcategory"] for r in scored) / n if n else 0.0
    format_errors = sum(r["status"] == "format_error" for r in scored)
    infra_errors = len(rows) - n
    if infra_errors:
        verdict = "INCOMPLETE"
    elif category >= CATEGORY_MIN and subcategory >= SUBCATEGORY_MIN and format_errors == 0:
        verdict = "PASS"
    else:
        verdict = "FAIL"
    return {
        "scored_cases": n,
        "category_accuracy": round(category, 4),
        "subcategory_accuracy": round(subcategory, 4),
        "format_errors": format_errors,
        "unresolved_infra_errors": infra_errors,
        "verdict": verdict,
    }


def run_case(case: dict, api_key: str) -> dict:
    from apps.wardrobe.analysis import AnalysisError, analyze_image

    raw = (EVAL_DIR / case["file"]).read_bytes()
    last = None
    for _ in range(RETRIES + 1):
        started = time.monotonic()
        try:
            attributes, _details = analyze_image(raw, api_key)
            seconds = round(time.monotonic() - started, 2)
            return {"status": "ok", "output": {k: attributes.get(k) for k in ("name", "category", "subcategory", "colors")},
                    "correct": score(case, attributes), "seconds": seconds}
        except AnalysisError as error:
            last = error.code
            if error.code in INFRA_ERRORS:
                time.sleep(2)
                continue
            if error.code in SETUP_ERRORS:  # 키·잔액·모델 설정 문제는 모델의 판단이 아니므로 실행을 멈춘다
                sys.exit(f"설정 오류로 평가를 멈춥니다: {error.code}")
            status = "format_error" if error.code in FORMAT_ERRORS else "rejected"  # IMAGE_*: 모델이 사진을 거부함
            return {"status": status, "error": error.code, "correct": score(case, None),
                    "seconds": round(time.monotonic() - started, 2)}
    return {"status": "infra_error", "error": last, "correct": score(case, None)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, help="앞에서부터 N개만 실행 (확인용, 공식 결과 아님)")
    args = parser.parse_args()

    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.test_settings")
    import django

    django.setup()
    from django.conf import settings

    from apps.wardrobe.analysis import model_name

    api_key = settings.OPENROUTER_API_KEY
    if not api_key:
        sys.exit("OPENROUTER_API_KEY가 설정되지 않았습니다.")
    cases = json.loads((EVAL_DIR / "cases.json").read_text(encoding="utf-8"))["cases"]
    if args.limit:
        cases = cases[: args.limit]

    rows = []
    for case in cases:
        result = run_case(case, api_key)
        rows.append({"id": case["id"], "file": case["file"], "expect": case["expect"], **result})
        mark = "OK " if result["correct"]["category"] and result["correct"]["subcategory"] else "MISS"
        print(f"{mark} {case['id']} {case['file']} -> {result.get('output', result.get('error'))}", flush=True)

    summary = summarize(rows)
    times = [r["seconds"] for r in rows if r.get("seconds") is not None]
    report = {
        "executed_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "model": model_name(),
        "cases_version": 1,
        "official": not args.limit,
        "thresholds": {"category": CATEGORY_MIN, "subcategory": SUBCATEGORY_MIN, "format_errors": 0},
        **summary,
        "median_seconds": round(statistics.median(times), 2) if times else None,
        "failures": [{"id": r["id"], "file": r["file"], "expect": r["expect"], "got": r.get("output"), "error": r.get("error")}
                     for r in rows if not (r["correct"]["category"] and r["correct"]["subcategory"])],
        "cases": rows,
    }
    out_dir = EVAL_DIR / "results"
    out_dir.mkdir(exist_ok=True)
    path = out_dir / f"{datetime.now():%Y%m%d-%H%M%S}.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("model", "scored_cases", "category_accuracy", "subcategory_accuracy",
                                             "format_errors", "unresolved_infra_errors", "verdict", "median_seconds")},
                     ensure_ascii=False))
    print(f"saved {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
