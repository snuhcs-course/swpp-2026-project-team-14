# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
"""Write the masked model's preprocessing output as JSON, to read and share inside the team.

    python scripts/kfashion/export_preprocessed.py [--sample 5] [--full]

sample_preprocessed.json: a few training photos step by step
  원본 라벨 -> 정제 (빈 슬롯 삭제, 리스트 풀기, 계절 추가) -> 토큰 -> 숫자 ID (masked_vocab.json)
--full: every de-duplicated outfit as JSON lines (train/val .jsonl.gz): cleaned garments + tokens.

Output goes next to the dataset (<repo>/kfashion_dataset/preprocessed), never into git: the labels
are AI Hub data and must not be redistributed.
"""

from __future__ import annotations

import argparse
import gzip
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from labels import CATALOG_DIR, KFASHION_DIR, SPLITS, label_zip, parse  # noqa: E402
from train_masked import load, outfit_tokens  # noqa: E402

from recommendation.masked import season_token  # noqa: E402
from recommendation.vocab import SLOTS  # noqa: E402

OUT = KFASHION_DIR.parent / "preprocessed"
EXAMPLE_ID = "1029473"  # the 레트로 example used in the team explanation


def cleaned(garments: dict) -> dict:
    return {slot: {**attrs, "계절": season_token(slot, attrs)} for slot, attrs in garments.items()}


def ids(tokens: list, vocab: dict) -> list:
    index = {tuple(v): i for i, v in enumerate(vocab["values"])}
    out = []
    for slot, key, value in tokens:
        vid = index.get((key, value))
        out.append({"token": [slot, key, value],
                    "slot_id": vocab["slots"].index(slot),
                    "key_id": vocab["keys"].index(key) if key in vocab["keys"] else None,
                    "value_id": vid,
                    **({"note": "학습 데이터에 20번 미만 나온 값이라 사전에서 빠짐"} if vid is None else {})})
    return out


def sample(n: int, seed: int) -> dict:
    vocab = json.loads((CATALOG_DIR / "masked_vocab.json").read_text(encoding="utf-8"))
    rng = random.Random(seed)
    with label_zip("train") as z:
        names = [i.filename for i in z.infolist() if not i.is_dir()]
        chosen = [m for m in names if m.endswith(f"/{EXAMPLE_ID}.json")][:1]
        chosen += rng.sample(names, n * 10)
        examples = []
        for member in chosen:
            raw = json.loads(z.read(member).decode("utf-8"))
            photo = parse("train", member, z.read(member))
            garments = {s: g.attrs for s, g in photo.garments.items() if g.attrs.get("카테고리") and g.attrs.get("색상")}
            if len(garments) < 2 and member not in chosen[:1]:
                continue  # show outfits with at least two garments
            outfit = {"style": photo.style, "garments": garments}
            toks = outfit_tokens(outfit)
            examples.append({
                "image_id": photo.image_id,
                "file": photo.file,
                "1_원본_라벨링": raw["데이터셋 정보"]["데이터셋 상세설명"]["라벨링"],
                "2_정제": {"스타일": photo.style, **cleaned(garments)},
                "3_토큰": [list(t) for t in toks],
                "4_숫자_ID": ids(toks, vocab),
                "중복_제거_키": [photo.stem, [[s, garments[s].get("카테고리"), garments[s].get("색상"), garments[s].get("핏")]
                                          for s in SLOTS if s in garments]],
            })
            if len(examples) >= n:
                break
    return {
        "설명": {
            "1_원본_라벨링": "AI Hub K-Fashion 라벨 JSON의 '라벨링' 부분 그대로",
            "2_정제": "빈 슬롯 삭제, [{...}] 리스트를 객체 하나로 풀기, 카테고리·색상 없는 옷 제외, 옷마다 규칙 기반 계절(여름/간절기/겨울) 추가",
            "3_토큰": "[슬롯, 속성, 값]. 스타일 토큰 1개 + 옷마다 단일 속성 8개, 소재≤2·프린트≤2·디테일≤3, 계절. 최대 48개",
            "4_숫자_ID": "masked_vocab.json 기준. 토큰 벡터 = 슬롯 임베딩[slot_id] + 속성 임베딩[key_id] + 값 임베딩[value_id] (128차원). value_id 0=패딩, 1=[MASK]",
            "중복_제거_키": "(상품 파일명에서 컷 번호를 뗀 이름, 슬롯별 카테고리·색상·핏)이 같은 사진은 한 번만 학습",
        },
        "사전_크기": {"슬롯": len(vocab["slots"]), "속성": len(vocab["keys"]), "값": len(vocab["values"])},
        "예시": examples,
    }


def full() -> None:
    for split in SPLITS:
        path = OUT / f"{split}_preprocessed.jsonl.gz"
        n = 0
        with gzip.open(path, "wt", encoding="utf-8") as f:
            for o in load(split):
                f.write(json.dumps({"style": o["style"], "garments": cleaned(o["garments"]),
                                    "tokens": [list(t) for t in outfit_tokens(o)]}, ensure_ascii=False) + "\n")
                n += 1
        print(f"{path} ({n} outfits, {path.stat().st_size // 1024 // 1024} MB)", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", type=int, default=5)
    ap.add_argument("--full", action="store_true")
    ap.add_argument("--seed", type=int, default=4)
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / "sample_preprocessed.json"
    path.write_text(json.dumps(sample(args.sample, args.seed), ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{path} written", flush=True)
    if args.full:
        full()


if __name__ == "__main__":
    main()
