"""Runs the recommendation evaluation in RUBRIC.md and writes results/<time>.json.

    python eval/recommendation/run.py              # both parts (part B needs kfashion_dataset/ locally)
    python eval/recommendation/run.py --part rules

No Django and no network: the engine runs on its shipped models, as in the API.
"""
from __future__ import annotations

import argparse
import copy
import gzip
import json
import statistics
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1]))

from recommendation import preference  # noqa: E402
from recommendation.body import Body, UnknownBody  # noqa: E402
from recommendation.catalog import Catalog, Item  # noqa: E402
from recommendation.labeling import label  # noqa: E402
from recommendation.search import RecommendError, Request, recommend  # noqa: E402
from recommendation.wardrobe import to_item  # noqa: E402

VAL = HERE.parents[2] / "kfashion_dataset" / "preprocessed" / "val_preprocessed.jsonl.gz"
PICK_JITTER = 0.05  # apps/outfits/views.py: the app's small random spread between near-best outfits
SLOT = {"top": "상의", "bottom": "하의", "outerwear": "아우터"}
WINTER_MATERIALS = {"패딩", "퍼", "무스탕", "플리스", "헤어 니트"}
SUMMER_MATERIALS = {"린넨", "시폰", "메시"}
RULES_TARGET, BAD_TARGET = 0.95, 0.90


# ------------------------------------------------------------------ part A: rule compliance
def cm(dims: dict, key: str) -> float | None:
    v = (dims or {}).get(key)
    v = v.get("value") if isinstance(v, dict) else v
    return float(v) if isinstance(v, (int, float)) else None


def too_small(garment: dict, body: dict) -> bool:
    """R2, from the garment's own measurements (not from the engine's labels)."""
    d, kind = garment["dimensions"], garment["attributes"]["category"]
    if kind in ("top", "outerwear"):
        half = cm(d, "chest_width_half")
        return half is not None and 2 * half < body["chest"]
    hip, waist = cm(d, "hip_width_half"), cm(d, "waist_width_half")
    return (hip is not None and 2 * hip < body["hip"]) or (waist is not None and 2 * waist < body["waist"] - 2)


def item_weather(temp: float, slot: str, attrs: dict) -> list[str]:
    """R3 for one garment: the rubric's table (기온별 옷차림)."""
    out, materials = [], set(attrs.get("소재") or [])
    sleeve, length, category = attrs.get("소매기장"), attrs.get("기장"), attrs.get("카테고리")
    if temp >= 23 and slot == "아우터":
        out.append("outerwear at 23 °C or more")
    if temp >= 23 and materials & WINTER_MATERIALS:
        out.append("winter material at 23 °C or more")
    if temp < 12 and (sleeve in ("민소매", "캡", "없음") or materials & SUMMER_MATERIALS):
        out.append("sleeveless or summer material under 12 °C")
    if temp < 5 and (sleeve == "반팔" or (slot == "하의" and length == "미니")):
        out.append("short sleeves or mini under 5 °C")
    if temp < 20 and slot == "하의" and length == "미니" and category != "스커트":
        out.append("shorts under 20 °C")
    return out


def check_outfit(case: dict, outfit: dict, notes: list[str], locked: str | None,
                 fits_weather: set[str] | None = None) -> dict:
    """`fits_weather`: slots for which the wardrobe has a garment that passes R2 and R3. A disclosed
    exception only counts when the wardrobe has none (RUBRIC.md); None means it is not known."""
    garments = {f"w:{g['id']}": g for g in case["garments"]}
    possible = fits_weather or set()
    items = outfit["items"]
    violations, disclosed = [], []
    for it in items:
        if it["id"] not in garments:
            violations.append(f"R1 not owned: {it['id']}")
            continue
        if too_small(garments[it["id"]], case["body"]):
            violations.append(f"R2 too small: {it['id']}")
        if it["id"] == locked:
            continue  # the user chose it
        for v in item_weather(case["temp"], it["slot"], it["attrs"]):
            note = any(n.startswith(f"오늘 날씨에 맞는 {it['slot']}") for n in notes) and it["slot"] not in possible
            (disclosed if note else violations).append(f"R3 {v}: {it['id']}")
    if case["temp"] < 9 and not any(it["slot"] == "아우터" for it in items):
        note = any("아우터가 필요한 날씨인데" in n for n in notes) and "아우터" not in possible
        (disclosed if note else violations).append("R3 no outerwear under 9 °C")
    if locked and locked not in {it["id"] for it in items}:
        violations.append(f"R4 lost the lock: {locked}")
    return {"key": outfit["key"], "items": [it["id"] for it in items], "violations": violations,
            "disclosed": disclosed}


def weather_ok(case: dict, items: list[Item]) -> list[Item]:
    """Garments that pass R2 and R3 on their own."""
    by_id = {f"w:{g['id']}": g for g in case["garments"]}
    return [i for i in items if not too_small(by_id[i.id], case["body"]) and not item_weather(case["temp"], i.slot, i.attrs)]


def eligible_combinations(case: dict, items: list[Item]) -> int:
    """Tops × bottoms that pass R2 and R3 (for the R5 exemption)."""
    ok = weather_ok(case, items)
    return sum(1 for i in ok if i.slot == "상의") * sum(1 for i in ok if i.slot == "하의")


def run_rules(catalog: Catalog, cases: list[dict]) -> dict:
    rows, scored, passed, disclosed_count, by_rule = [], 0, 0, 0, {f"R{n}": 0 for n in range(1, 6)}
    for case in cases:
        body = Body(**case["body"])
        items = []
        for g in case["garments"]:
            labels = label(g["attributes"], g["dimensions"], body, g.get("details"))
            item = to_item({**g, "labels": labels})
            if item:
                items.append(item)
        combos = eligible_combinations(case, items)
        fits_weather = {i.slot for i in weather_ok(case, items)}

        def ask(**extra):
            req = Request(body=body, pref_fit=case["pref_fit"], styles=case["styles"], temp=case["temp"],
                          mode="wardrobe", wardrobe=copy.deepcopy(items), preference=preference.empty(),
                          count=3, seed=case["seed"], jitter=PICK_JITTER, **extra)
            return recommend(catalog, req)

        results = {}
        locked = f"w:{case['lock']}"
        for name, extra in (("basic", {}), ("again", None), ("locked", {"fixed": {"상의": locked}})):
            if name == "again":
                extra = {"seen_outfits": [o["key"] for o in results.get("basic", {}).get("outfits", [])]}
            try:
                results[name] = ask(**extra)
            except RecommendError as e:
                results[name] = {"error": e.code, "outfits": [], "notes": []}
        for name, res in results.items():
            if "error" in res:
                ok = res["error"] == "WARDROBE_TOO_SMALL" and combos == 0
                scored += 3
                passed += 3 if ok else 0
                rows.append({"case": case["id"], "request": name, "error": res["error"], "pass": ok})
                continue
            keys = [o["key"] for o in res["outfits"]]
            for o in res["outfits"]:
                c = check_outfit(case, o, res["notes"], locked if name == "locked" else None, fits_weather)
                if keys.count(o["key"]) > 1 and combos >= 6:
                    c["violations"].append("R5 repeated within the response")
                if name == "again" and combos >= 6 and o["key"] in {x["key"] for x in results["basic"]["outfits"]}:
                    c["violations"].append("R5 repeated an outfit already shown")
                for v in c["violations"]:
                    by_rule[v[:2]] += 1
                disclosed_count += bool(c["disclosed"])
                scored += 1
                passed += not c["violations"]
                rows.append({"case": case["id"], "request": name, "temp": case["temp"], "combinations": combos,
                             "pass": not c["violations"], **c})
            if len(res["outfits"]) < 3:
                missing = 3 - len(res["outfits"])
                scored += missing
                rows.append({"case": case["id"], "request": name, "missing_outfits": missing, "pass": False})
    rate = passed / scored if scored else 0.0
    return {"scored_outfits": scored, "passed": passed, "rate": round(rate, 4), "target": RULES_TARGET,
            "verdict": "PASS" if rate >= RULES_TARGET else "FAIL", "violations_by_rule": by_rule,
            "outfits_with_disclosed_exception": disclosed_count, "rows": rows}


# ------------------------------------------------------------------ part B: clearly bad outfits
def records(indexes: set[int]) -> dict[int, dict]:
    out = {}
    with gzip.open(VAL, "rt", encoding="utf-8") as f:
        for index, line in enumerate(f):
            if index in indexes:
                out[index] = json.loads(line)
    return out


def broken(pair: dict, rec: dict, recs: dict) -> dict:
    g = copy.deepcopy(rec["garments"])
    kind = pair["type"]
    if kind == "winter_shorts":
        g["하의"].update({"카테고리": "팬츠", "기장": "미니"})
    elif kind == "winter_sleeveless":
        g["상의"]["소매기장"] = "민소매"
    elif kind == "summer_padding":
        g["아우터"] = {"카테고리": "패딩", "색상": "블랙", "소재": ["패딩"], "기장": "노말", "소매기장": "긴팔",
                    "핏": "노멀", "계절": "겨울"}
    elif kind == "style_clash":
        g["하의"] = copy.deepcopy(recs[pair["bottom_from"]]["garments"]["하의"])
    return g


def temp_of(pair: dict, rec: dict) -> float:
    if pair["temp"] is not None:
        return pair["temp"]
    seasons = {x.get("계절") for x in rec["garments"].values()} - {None}
    return 27 if seasons == {"여름"} else 16


def score(catalog: Catalog, garments: dict, style: str, temp: float, sporty_bottom: bool = False) -> dict:
    items = [Item(id=f"w:{slot}", slot=slot, attrs=attrs, source="wardrobe", labeled=True,
                  style="스포티" if (sporty_bottom and slot == "하의") else style,
                  styles=("스포티",) if (sporty_bottom and slot == "하의") else (style,))
             for slot, attrs in garments.items()]
    req = Request(body=UnknownBody(), temp=temp, mode="wardrobe", wardrobe=items, preference=preference.empty(),
                  fixed={i.slot: i.id for i in items}, count=1, seed=0)
    o = recommend(catalog, req)["outfits"][0]
    return {"raw_total": o["raw_total"], "compat": o["components"]["compat"], "season": o["components"]["season"]}


def run_bad(catalog: Catalog, pairs: list[dict]) -> dict:
    needed = {p["record"] for p in pairs} | {p["bottom_from"] for p in pairs if "bottom_from" in p}
    recs = records(needed)
    rows, by_type = [], {}
    for p in pairs:
        rec, temp = recs[p["record"]], temp_of(p, recs[p["record"]])
        try:
            real = score(catalog, rec["garments"], rec["style"], temp)
            bad = score(catalog, broken(p, rec, recs), rec["style"], temp, sporty_bottom=p["type"] == "style_clash")
            ok = real["raw_total"] > bad["raw_total"]
            row = {"id": p["id"], "type": p["type"], "record": p["record"], "temp": temp, "real": real,
                   "broken": bad, "margin": round(real["raw_total"] - bad["raw_total"], 4), "pass": ok}
        except RecommendError as e:
            row = {"id": p["id"], "type": p["type"], "record": p["record"], "temp": temp, "error": e.code, "pass": False}
        rows.append(row)
        by_type.setdefault(p["type"], []).append(row)
    rate = sum(r["pass"] for r in rows) / len(rows)
    summary = {t: {"pass_rate": round(sum(r["pass"] for r in rs) / len(rs), 4),
                   "median_margin": round(statistics.median(r.get("margin", 0.0) for r in rs), 4)}
               for t, rs in by_type.items()}
    return {"pairs": len(rows), "rate": round(rate, 4), "target": BAD_TARGET,
            "verdict": "PASS" if rate >= BAD_TARGET else "FAIL", "by_type": summary, "rows": rows}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--part", choices=["rules", "bad", "all"], default="all")
    args = parser.parse_args()
    cases = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))
    catalog = Catalog.load_models()
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, cwd=HERE).stdout.strip()
    out = {"commit": commit, "run_at": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    if args.part in ("rules", "all"):
        out["rules"] = run_rules(catalog, cases["rules"])
        r = out["rules"]
        print(f"A rule compliance: {r['passed']}/{r['scored_outfits']} = {r['rate']:.1%} → {r['verdict']}; "
              f"violations {r['violations_by_rule']}; disclosed {r['outfits_with_disclosed_exception']}")
    if args.part in ("bad", "all"):
        out["bad_outfits"] = run_bad(catalog, cases["bad_outfits"])
        b = out["bad_outfits"]
        print(f"B clearly bad outfits: {b['rate']:.1%} → {b['verdict']}; {b['by_type']}")
    (HERE / "results").mkdir(exist_ok=True)
    path = HERE / "results" / (datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
    path.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", path.relative_to(HERE.parents[1]))


if __name__ == "__main__":
    main()
