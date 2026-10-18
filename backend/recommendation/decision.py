# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-11, reviewed by Dongkun Moon
"""Decision layer with Jev's contract: a state, typed choice questions, and for each question a choice
with a probability for every option and a confidence.

Jev (TypeSafe AI) answers these questions with a hosted decision model. Without a Jev key the
LocalDecider answers them from our own scores: probabilities are a softmax of the option scores at
a temperature calibrated on held-out K-Fashion outfits (scripts/kfashion/calibrate_decision.py), and
confidence is how concentrated the distribution is (1 - normalised entropy), as Jev defines it.

Jev's own guidance is followed for the questions: option names are neutral IDs ("A1", "A2", ...),
the meaning lives in each option's definition, and a probability is a signal, not an authorisation
(apply only above a threshold; otherwise show alternatives).
"""

from __future__ import annotations

import json
import math
import os
import socket
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

DEFAULT_TEMPERATURE = 0.08  # replaced by the calibrated value when present
APPLY_AT = 0.5  # apply a change when its probability is at least this (and beats "keep")
SURE_AT = 0.85


@dataclass
class ChoiceQuestion:
    instructions: str
    options: dict[str, str]  # neutral option id -> definition (what choosing it means)
    scores: dict[str, float] = field(default_factory=dict)  # our own score per option (local decider)


@dataclass
class ChoiceAnswer:
    choice: str
    probabilities: dict[str, float]
    confidence: float
    decider: str

    def public(self) -> dict:
        return asdict(self)


def confidence_of(probs: list[float]) -> float:
    """How concentrated a distribution is: 1 - entropy / log(n)."""
    n = len(probs)
    if n <= 1:
        return 1.0
    h = -sum(p * math.log(p) for p in probs if p > 0)
    return max(0.0, 1.0 - h / math.log(n))


def softmax(scores: dict[str, float], temperature: float) -> dict[str, float]:
    top = max(scores.values())
    ex = {k: math.exp((v - top) / temperature) for k, v in scores.items()}
    z = sum(ex.values())
    return {k: v / z for k, v in ex.items()}


class LocalDecider:
    name = "local"

    def __init__(self, temperature: float | None = None, calibration_path: Path | None = None) -> None:
        self.temperature = temperature or DEFAULT_TEMPERATURE
        if temperature is None and calibration_path and calibration_path.exists():
            self.temperature = float(json.loads(calibration_path.read_text(encoding="utf-8"))["temperature"])

    def decide(self, state: str, questions: dict[str, ChoiceQuestion]) -> dict[str, ChoiceAnswer]:
        out = {}
        for qid, q in questions.items():
            probs = softmax(q.scores, self.temperature)
            choice = max(probs, key=probs.get)
            out[qid] = ChoiceAnswer(choice, {k: round(v, 4) for k, v in probs.items()},
                                    round(confidence_of(list(probs.values())), 4), self.name)
        return out


class JevError(Exception):
    pass


class JevDecider:
    """Calls Jev through TypeSafe (TYPESAFE_API_KEY) or AIML API (AIMLAPI_KEY)."""

    name = "jev"
    ENDPOINTS = {
        "TYPESAFE_API_KEY": ("https://api.typesafe.ai/v1/systemone", "jev-latest"),
        "AIMLAPI_KEY": ("https://api.aimlapi.com/v1/decisions", "typesafe/jev"),
    }

    def __init__(self, env_name: str, key: str) -> None:
        self.url, self.model = self.ENDPOINTS[env_name]
        self.key = key

    @classmethod
    def from_env(cls) -> "JevDecider | None":
        from dotenv import dotenv_values

        env = {**dotenv_values(Path(__file__).resolve().parents[1] / ".env"), **os.environ}
        for name in cls.ENDPOINTS:
            if env.get(name):
                return cls(name, env[name])
        return None

    def decide(self, state: str, questions: dict[str, ChoiceQuestion]) -> dict[str, ChoiceAnswer]:
        payload = {
            "model": self.model,
            "state": state,
            "questions": {qid: {"type": "choice", "instructions": q.instructions, "criteria": q.options}
                          for qid, q in questions.items()},
        }
        req = Request(self.url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), method="POST",
                      headers={"Content-Type": "application/json", "Authorization": f"Bearer {self.key}"})
        try:
            with urlopen(req, timeout=10) as r:
                data = json.loads(r.read(512 * 1024))
        except HTTPError as e:
            raise JevError(f"HTTP {e.code}") from None
        except (URLError, socket.timeout, TimeoutError, ValueError) as e:
            raise JevError(type(e).__name__) from None
        answers = data.get("answers", data)
        out = {}
        for qid in questions:
            a = answers.get(qid) or {}
            if "choice" not in a:
                raise JevError("missing answer")
            out[qid] = ChoiceAnswer(a["choice"], a.get("probabilities", {a["choice"]: 1.0}),
                                    float(a.get("confidence", 0.0)), self.name)
        return out


def decide(state: str, questions: dict[str, ChoiceQuestion], local: LocalDecider,
           jev: JevDecider | None) -> tuple[dict[str, ChoiceAnswer], float, str | None]:
    """(answers, elapsed ms, error). Jev first when configured; local answers on any Jev failure."""
    t = time.perf_counter()
    error = None
    if jev is not None:
        try:
            return jev.decide(state, questions), (time.perf_counter() - t) * 1000, None
        except JevError as e:
            error = f"Jev 호출 실패({e}) → 로컬 결정으로 대체"
    answers = local.decide(state, questions)
    return answers, (time.perf_counter() - t) * 1000, error
