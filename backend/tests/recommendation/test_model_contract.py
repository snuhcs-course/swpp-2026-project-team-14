"""Stored wardrobe labels must survive conversion to the shipped model vocabulary."""
import logging

import numpy as np
import pytest

from recommendation import vocab
from recommendation.catalog import MODEL_DIR
from recommendation.compat import CompatModel, FEATURE_TYPES
from recommendation.labeling import label, validate_labels
from recommendation.masked import MaskedModel, garment_tokens
from recommendation.wardrobe import to_item


@pytest.fixture(scope="module")
def model():
    return MaskedModel(MODEL_DIR)


def test_every_editable_value_has_a_trained_token(model):
    choices = {
        "카테고리": [v for values in vocab.CATEGORIES.values() for v in values],
        "색상": list(vocab.COLORS), "서브색상": list(vocab.COLORS),
        "핏": vocab.UPPER_FITS + vocab.BOTTOM_FITS,
        "기장": [v for values in vocab.LENGTHS.values() for v in values],
        "소매기장": vocab.SLEEVES, "계절": vocab.SEASONS, "프린트": vocab.PRINTS,
        "넥라인": vocab.NECKLINES, "옷깃": vocab.COLLARS,
        "디테일": vocab.DETAILS, "소재": vocab.MATERIALS,
    }
    missing = [(k, v) for k, values in choices.items() for v in values
               if (k, vocab.normalize_value(v)) not in model.index]
    assert not missing


def test_saved_labels_are_encoded_and_onnx_inference_succeeds(model):
    attrs = {"category": "top", "subcategory": "tshirt", "colors": ["#FFFFFF", "#24344B", "#C83C3C"]}
    labels = {**label(attrs, None), "season": "겨울", "prints": ["하운즈 투스"], "confirmed": True}
    item = to_item({"id": "saved", "attributes": attrs, "labels": validate_labels(labels, "top")})
    tokens = garment_tokens(item.slot, item.attrs)
    assert ("상의", "서브색상", "네이비") in tokens
    assert ("상의", "계절", "겨울") in tokens
    assert all((k, vocab.normalize_value(v)) in model.index for _, k, v in tokens)
    scores = model.slot_logprobs({item.slot: item.attrs}, None, "하의")
    assert scores and all(np.isfinite(v).any() for v in scores.values())
    dataset = {**item.attrs, "프린트": ["하운즈\u00a0투스"]}
    same = model.slot_logprobs({item.slot: dataset}, None, "하의")
    assert all(np.array_equal(scores[k], same[k]) for k in scores)
    absent = model.slot_logprobs({item.slot: {**item.attrs, "프린트": []}}, None, "하의")
    assert any(not np.array_equal(scores[k], absent[k]) for k in scores)


def test_unknown_tokens_are_reported(model, caplog):
    with caplog.at_level(logging.WARNING, logger="recommendation.masked"):
        model.slot_logprobs({"상의": {"프린트": ["미등록무늬"]}}, None, "하의")
    assert "Unknown model token: 프린트=미등록무늬" in caplog.text


def test_pmi_uses_the_same_space_normalization():
    model = CompatModel({"상의|하의|프린트|프린트|하운즈\u00a0투스|체크": [0.7, 30]},
                        [1.0] * len(FEATURE_TYPES), 0)
    assert model.lookup("상의", "하의", "프린트", "프린트", "하운즈 투스", "체크") == (0.7, 30)
