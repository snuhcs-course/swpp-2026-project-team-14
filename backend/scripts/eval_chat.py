"""채팅 LLM 해석 평가: python scripts/eval_chat.py

eval/chat/cases.json의 고정 케이스를 실제 모델(chat_intent.parse)로 해석하고,
eval/chat/RUBRIC.md의 기준으로 자동 채점한다. 결과는 eval/chat/results/에 JSON으로 저장한다.
API 키는 GEMINI_API_KEY 환경 변수 또는 backend/.env에서 읽는다 (서버·DB 불필요).
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
EVAL_DIR = ROOT / "eval" / "chat"

PASS_RATE = 0.8  # RUBRIC.md: 케이스 통과율 80% 이상
FORMAT_ERRORS = {"ValueError", "TypeError", "KeyError", "IndexError", "AttributeError"}  # 응답 형식·스키마 위반
# GeminiError 중 모델 응답 자체가 비었거나 읽을 수 없는 경우는 형식 오류, 나머지(한도·인증·시간 초과·연결)는 인프라 오류
FORMAT_GEMINI_CODES = {"AI_INVALID_RESPONSE"}


def _has_want(wants: list[dict], want: str, slot: str | None) -> bool:
    """slot이 None이면 어느 slot이든, 지정되면 그 slot 또는 None(전체)이면 인정."""
    return any(w["want"] == want and (slot is None or w["slot"] in (slot, None)) for w in wants)


def score(case: dict, output: dict, wardrobe_ids: set[str]) -> tuple[list[str], list[str]]:
    """(실패 사유, 치명적 오류 사유)를 돌려준다. 둘 다 비어 있으면 통과."""
    expect = case["expect"]
    failed: dict[str, str] = {}

    def check(key: str, ok: bool, reason: str) -> None:
        if key in expect and not ok:
            failed[key] = reason

    wants = output["wants"]
    check("action", output["action"] == expect.get("action"), f"action={output['action']}")
    check("wants_include", all(_has_want(wants, w["want"], w["slot"]) for w in expect.get("wants_include", [])),
          f"wants={wants}")
    check("wants_exclude", not any(w["want"] in expect.get("wants_exclude", []) for w in wants), f"wants={wants}")
    for field in ("change_slots", "keep_slots", "avoid_colors", "target_colors"):
        check(f"{field}_include", set(expect.get(f"{field}_include", [])) <= set(output[field]),
              f"{field}={output[field]}")
        check(f"{field}_exclude", not set(expect.get(f"{field}_exclude", [])) & set(output[field]),
              f"{field}={output[field]}")
    check("target_garment_ids", sorted(output["target_garment_ids"]) == sorted(expect.get("target_garment_ids", [])),
          f"target_garment_ids={output['target_garment_ids']}")
    check("remove_outerwear", output["remove_outerwear"] == expect.get("remove_outerwear"),
          f"remove_outerwear={output['remove_outerwear']}")

    critical = [f"{key}: {failed[key]}" for key in case.get("critical", []) if key in failed]
    invented = [gid for gid in output["target_garment_ids"] if gid not in wardrobe_ids]
    if invented:
        critical.append(f"옷장에 없는 옷 ID: {invented}")
    both = set(output["keep_slots"]) & set(output["change_slots"])
    if both:
        critical.append(f"keep·change 동시: {sorted(both)}")
    return [f"{key}: {reason}" for key, reason in failed.items()], critical


def context(case: dict, wardrobe: list[dict]) -> dict:
    """서버(apps/outfits/chat.py)가 parse에 넘기는 것과 같은 모양의 문맥."""
    by_id = {g["id"]: g for g in wardrobe}
    return {
        "current": [{"slot": by_id[gid]["slot"], "name": by_id[gid]["name"]} for gid in case["current"]],
        "locked_slots": [by_id[gid]["slot"] for gid in case["locked"]],
        "history": case["history"],
        "wardrobe": wardrobe,
    }


class _ErrorType(logging.Handler):
    """chat_intent가 삼키는 예외의 종류를 경고 로그에서 꺼낸다 (형식 오류 vs 인프라 오류 구분)."""

    def __init__(self) -> None:
        super().__init__(logging.WARNING)
        self.last: str | None = None
        self.gemini_code: str | None = None  # 마지막 GeminiError의 code (로그에는 타입 이름만 남으므로 따로 기록)

    def emit(self, record: logging.LogRecord) -> None:
        if record.args:
            self.last = str(record.args[-1])


def run_case(chat_intent, case: dict, wardrobe: list[dict], errors: _ErrorType, retries: int, delay: float):
    """(output | None, 오류 종류 | None). 인프라 오류는 retries번까지 다시 시도한다."""
    for attempt in range(retries + 1):
        errors.last = errors.gemini_code = None
        try:
            output, _, _ = chat_intent.parse(case["request"], context(case, wardrobe))
            return output, None
        except chat_intent.ChatModelError:
            name = errors.gemini_code if errors.last == "GeminiError" and errors.gemini_code else errors.last
            kind = "format" if name in FORMAT_ERRORS or name in FORMAT_GEMINI_CODES else "infra"
            if kind == "format" or attempt == retries:
                return None, f"{kind}:{name}"
            time.sleep(delay * 4)
    return None, "infra:unknown"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", action="append", help="특정 케이스 id만 실행 (여러 번 지정 가능, 집계는 참고용)")
    parser.add_argument("--delay", type=float, default=1.0, help="호출 간격(초), 요청 한도 대비")
    parser.add_argument("--retries", type=int, default=2, help="인프라 오류 재시도 횟수")
    args = parser.parse_args()

    os.environ.setdefault("CHAT_PROVIDER", "gemini")
    from recommendation import chat_intent, gemini
    # 키가 없으면 parse가 ValueError로 실패해 형식 오류로 잘못 집계되므로 먼저 막는다
    if os.environ["CHAT_PROVIDER"] == "gemini" and not gemini.api_key():
        print("GEMINI_API_KEY가 없습니다. 환경 변수나 backend/.env에 설정해 주세요.")
        return 2

    data = json.loads((EVAL_DIR / "cases.json").read_text(encoding="utf-8"))
    wardrobe = data["wardrobe"]
    wardrobe_ids = {g["id"] for g in wardrobe}
    cases = [c for c in data["cases"] if not args.case or c["id"] in args.case]
    errors = _ErrorType()
    logging.getLogger("recommendation.chat_intent").addHandler(errors)
    original_generate = gemini.generate

    def recording_generate(*args, **kwargs):
        try:
            return original_generate(*args, **kwargs)
        except gemini.GeminiError as error:
            errors.gemini_code = error.code
            raise

    gemini.generate = recording_generate  # chat_intent는 gemini.generate를 모듈 속성으로 호출함

    rows = []
    for index, case in enumerate(cases):
        if index:
            time.sleep(args.delay)
        output, error = run_case(chat_intent, case, wardrobe, errors, args.retries, args.delay)
        if output is None:
            failed, critical = [f"모델 오류 {error}"], []
        else:
            failed, critical = score(case, output, wardrobe_ids)
        rows.append({"id": case["id"], "category": case["category"], "request": case["request"],
                     "passed": not failed and not critical, "failed": failed, "critical": critical,
                     "error": error, "output": output})
        mark = "PASS" if rows[-1]["passed"] else "FAIL"
        print(f"[{mark}] {case['id']:<9} {case['request']}")
        for reason in failed + [f"치명적 · {c}" for c in critical]:
            print(f"         - {reason}")

    infra = [r["id"] for r in rows if r["error"] and r["error"].startswith("infra")]
    scored = [r for r in rows if not (r["error"] and r["error"].startswith("infra"))]
    passed = sum(r["passed"] for r in scored)
    rate = passed / len(scored) if scored else 0.0
    critical = sum(len(r["critical"]) for r in scored)
    format_errors = sum(1 for r in scored if r["error"])
    complete = not infra and not args.case
    verdict = rate >= PASS_RATE and critical == 0 and format_errors == 0
    summary = {"cases": len(rows), "scored": len(scored), "passed": passed, "pass_rate": round(rate, 4),
               "critical_errors": critical, "format_errors": format_errors, "infra_errors": infra,
               "criteria": {"pass_rate": PASS_RATE, "critical_errors": 0, "format_errors": 0},
               "verdict": ("PASS" if verdict else "FAIL") if complete else "INCOMPLETE"}
    result = {"run_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
              "model": gemini.TEXT_MODEL, "provider": os.environ["CHAT_PROVIDER"],
              "cases_version": data["version"], "summary": summary, "results": rows}

    print(f"\n통과 {passed}/{len(scored)} ({rate:.1%}, 기준 {PASS_RATE:.0%} 이상) · 치명적 오류 {critical}건 · "
          f"형식 오류 {format_errors}건 → {summary['verdict']}")
    if infra:
        print(f"인프라 오류로 채점하지 못한 케이스: {infra} (다시 실행 필요, 이 결과는 쓰지 않음)")
    if complete:
        out_dir = EVAL_DIR / "results"
        out_dir.mkdir(exist_ok=True)
        path = out_dir / f"{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"결과 저장: {path.relative_to(ROOT)}")
    return 0 if verdict and complete else 1


if __name__ == "__main__":
    sys.exit(main())
