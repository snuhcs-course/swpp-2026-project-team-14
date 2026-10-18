# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
"""Holds one BodyAnalysisPipeline per process.

MediaPipe's landmarker is not thread-safe, so analyses are serialised with a lock. Loading the
model takes ~1 s, so it is created on first use and then reused.
"""

from __future__ import annotations

import threading

from body_analysis import AnalysisInput, AnalysisResult, BodyAnalysisPipeline

_lock = threading.Lock()
_pipeline: BodyAnalysisPipeline | None = None


def get_pipeline() -> BodyAnalysisPipeline:
    global _pipeline
    if _pipeline is None:
        from body_analysis.clothing import ClothingDetector
        from body_analysis.pose import MediaPipePoseEstimator
        from body_analysis.reference import ReferenceSet
        from body_analysis.regressor import MeasurementCorrector

        _pipeline = BodyAnalysisPipeline(
            MediaPipePoseEstimator(), MeasurementCorrector.load(), ClothingDetector.load(), ReferenceSet.load()
        )
    return _pipeline


def set_pipeline(pipeline: BodyAnalysisPipeline | None) -> None:
    """Replace the pipeline (tests inject one with a fake pose estimator)."""
    global _pipeline
    _pipeline = pipeline


def analyze(front: bytes, side: bytes, input_: AnalysisInput) -> AnalysisResult:
    with _lock:
        return get_pipeline().analyze(front, side, input_)
