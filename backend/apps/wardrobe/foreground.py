"""Optional U2Net foreground crop for a single flat garment, before HRNet inference."""
import hashlib
from pathlib import Path
from threading import Lock

import numpy as np
from django.conf import settings
from PIL import Image

from .analysis import AnalysisError

MODEL_SHA256 = '8d10d2f3bb75ae3b6d527c77944fc5e7dcd94b29809d47a739a7a728a912b491'
MODEL_URL = 'https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx'
_session = None
_lock = Lock()


def session():
    global _session
    with _lock:
        if _session is None:
            try:
                import onnxruntime as ort
                path = Path(settings.WARDROBE_MODEL_DIR) / 'u2net.onnx'
                with path.open('rb') as stream:
                    if hashlib.file_digest(stream, 'sha256').hexdigest() != MODEL_SHA256:
                        raise ValueError('Model integrity')
                options = ort.SessionOptions()
                options.intra_op_num_threads = 4
                options.inter_op_num_threads = 1
                _session = ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])
            except Exception as error:
                raise AnalysisError('FOREGROUND_NOT_CONFIGURED', 503) from error
        return _session


def foreground_crop(image):
    """Return a crop and original-pixel box; a bad/empty mask leaves the image unchanged."""
    engine = session()
    array = np.asarray(image.resize((320, 320), Image.Resampling.LANCZOS), dtype=np.float32)
    maximum = float(array.max())
    if maximum == 0:
        return image, (0, 0, *image.size), False
    array /= maximum
    array = (array - np.array([.485, .456, .406], dtype=np.float32)) / np.array([.229, .224, .225], dtype=np.float32)
    try:
        prediction = engine.run(None, {engine.get_inputs()[0].name: np.ascontiguousarray(array.transpose(2, 0, 1)[None])})[0][0, 0]
    except Exception as error:
        raise AnalysisError('FOREGROUND_FAILED', 503) from error
    span = float(prediction.max() - prediction.min())
    if not np.isfinite(prediction).all() or span < 1e-6:
        return image, (0, 0, *image.size), False
    mask = Image.fromarray(np.uint8((prediction - prediction.min()) / span >= .5) * 255)
    mask = mask.resize(image.size, Image.Resampling.NEAREST)
    coverage = np.count_nonzero(np.asarray(mask)) / (image.width * image.height)
    box = mask.getbbox()
    if box is None or not .02 <= coverage <= .95:
        return image, (0, 0, *image.size), False
    padding = max(4, round(max(box[2] - box[0], box[3] - box[1]) * .08))
    box = (max(0, box[0] - padding), max(0, box[1] - padding),
           min(image.width, box[2] + padding), min(image.height, box[3] + padding))
    cutout = Image.composite(image, Image.new('RGB', image.size, 'white'), mask)
    return cutout.crop(box), box, True
