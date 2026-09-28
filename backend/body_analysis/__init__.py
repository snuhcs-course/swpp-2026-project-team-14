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
