# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
"""StyleMate body analysis: photos + height → garment measurements. No Django dependency."""

from .pipeline import PIPELINE_VERSION, BodyAnalysisPipeline
from .types import AnalysisError, AnalysisInput, AnalysisResult, Clothing, Confidence, Gender, MeasurementType

__all__ = [
    "PIPELINE_VERSION",
    "AnalysisError",
    "AnalysisInput",
    "AnalysisResult",
    "BodyAnalysisPipeline",
    "Clothing",
    "Confidence",
    "Gender",
    "MeasurementType",
]
