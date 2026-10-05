import hashlib
import json
from io import BytesIO
from pathlib import Path
from threading import Lock
from time import perf_counter
from typing import NamedTuple

from django.conf import settings
from PIL import Image, ImageOps, UnidentifiedImageError

from .analysis import AnalysisError


class GarmentDefinition(NamedTuple):
    channel_start: int
    channel_stop: int
    measurement_paths: dict[str, tuple[int, ...]]


MODEL_ID = 'lygitdata/garmentiq'
MODEL_REVISION = '5f02016e9ad3a4aa171fa9199423a437170f5afe'
INPUT_WIDTH, INPUT_HEIGHT = 288, 384
MAX_FRAME_BYTES = 1024 * 1024
MIN_SCORE = 0.4
MIN_PATH_DISTANCE_SQUARED = 0.0004
MIN_THIGH_WIDTH = 0.02
PARALLEL_TOLERANCE = 1e-6
GARMENTS = {
    'short_sleeve_top': GarmentDefinition(
        channel_start=0,
        channel_stop=25,
        measurement_paths={
            'shoulder_width': (7, 25),
            'chest_width_half': (12, 20),
            'armhole_straight': (7, 12),
            'sleeve_length': (25, 24, 23),
            'total_length': (1, 16),
            'hem_width_half': (15, 17),
            'cuff_width_half': (23, 22),
        },
    ),
    'long_sleeve_top': GarmentDefinition(
        channel_start=25,
        channel_stop=58,
        measurement_paths={
            'shoulder_width': (7, 33),
            'chest_width_half': (16, 24),
            'armhole_straight': (7, 16),
            'sleeve_length': (33, 32, 31, 30, 29),
            'total_length': (1, 20),
            'hem_width_half': (19, 21),
            'cuff_width_half': (29, 28),
        },
    ),
    'short_sleeve_outerwear': GarmentDefinition(
        channel_start=58,
        channel_stop=89,
        measurement_paths={
            'shoulder_width': (7, 25),
            'chest_width_half': (12, 20),
            'armhole_straight': (7, 12),
            'sleeve_length': (25, 24, 23),
            'total_length': (1, 16),
            'hem_width_half': (15, 17),
            'cuff_width_half': (23, 22),
        },
    ),
    'long_sleeve_outerwear': GarmentDefinition(
        channel_start=89,
        channel_stop=128,
        measurement_paths={
            'shoulder_width': (7, 33),
            'chest_width_half': (16, 24),
            'armhole_straight': (7, 16),
            'sleeve_length': (33, 32, 31, 30, 29),
            'total_length': (1, 20),
            'hem_width_half': (19, 21),
            'cuff_width_half': (29, 28),
        },
    ),
    'trousers': GarmentDefinition(
        channel_start=168,
        channel_stop=182,
        measurement_paths={
            'waist_width_half': (1, 3),
            'hip_width_half': (4, 14),
            'total_length': (1, 4, 5, 6),
            'rise_front': (2, 9),
            'inseam': (9, 8, 7),
            'hem_opening': (6, 7),
        },
    ),
    'shorts': GarmentDefinition(
        channel_start=158,
        channel_stop=168,
        measurement_paths={
            'waist_width_half': (1, 3),
            'hip_width_half': (4, 10),
            'total_length': (1, 4, 5),
            'rise_front': (2, 7),
            'inseam': (7, 6),
            'hem_opening': (5, 6),
        },
    ),
    'skirt': GarmentDefinition(
        channel_start=182,
        channel_stop=190,
        measurement_paths={
            'waist_width_half': (1, 3),
            'hip_width_half': (4, 8),
            'total_length': (1, 4, 5),
        },
    ),
}
_session = None
_lock = Lock()


def session():
    global _session
    with _lock:
        if _session is None:
            directory = Path(settings.WARDROBE_MODEL_DIR)
            model = directory / 'hrnet.onnx'
            try:
                metadata = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
                with model.open('rb') as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                if metadata['sha256'] != digest or metadata['revision'] != MODEL_REVISION:
                    raise ValueError('Model integrity')
                import onnxruntime as ort
                options = ort.SessionOptions()
                options.intra_op_num_threads = 4
                options.inter_op_num_threads = 1
                options.enable_cpu_mem_arena = False
                options.enable_mem_pattern = False
                options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
                candidate = ort.InferenceSession(str(model), sess_options=options, providers=['CPUExecutionProvider'])
                if (candidate.get_inputs()[0].shape != [1, 3, INPUT_HEIGHT, INPUT_WIDTH]
                        or candidate.get_outputs()[0].shape != [1, 294, 96, 72]):
                    raise ValueError('Model input shape')
                _session = candidate
            except Exception as error:
                raise AnalysisError('LANDMARK_NOT_CONFIGURED', 503) from error
        return _session


def read_frame(raw):
    if not raw or len(raw) > MAX_FRAME_BYTES:
        raise AnalysisError('IMAGE_TOO_LARGE', 413)
    try:
        with Image.open(BytesIO(raw)) as original:
            if original.format not in ('JPEG', 'PNG', 'WEBP') or getattr(original, 'n_frames', 1) != 1:
                raise AnalysisError('INVALID_IMAGE', 400)
            if original.width * original.height > 1_000_000:
                raise AnalysisError('IMAGE_TOO_LARGE', 413)
            image = ImageOps.exif_transpose(original).convert('RGB')
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as error:
        raise AnalysisError('INVALID_IMAGE', 400) from error
    return image


def prepare_frame(raw):
    return image_tensor(read_frame(raw))


def image_tensor(image):
    import numpy as np
    width, height = image.size
    factor = min(INPUT_WIDTH / width, INPUT_HEIGHT / height)
    scaled_w, scaled_h = max(1, round(width * factor)), max(1, round(height * factor))
    left, top = (INPUT_WIDTH - scaled_w) // 2, (INPUT_HEIGHT - scaled_h) // 2
    canvas = Image.new('RGB', (INPUT_WIDTH, INPUT_HEIGHT))
    canvas.paste(image.resize((scaled_w, scaled_h), Image.Resampling.BILINEAR), (left, top))
    array = np.asarray(canvas, dtype=np.float32) / 255.0
    array = (array - np.array([.485, .456, .406], dtype=np.float32)) / np.array([.229, .224, .225], dtype=np.float32)
    return np.ascontiguousarray(array.transpose(2, 0, 1)[None]), (scaled_w, scaled_h, left, top)


def decode(heatmaps, garment, transform):
    definition = GARMENTS[garment]
    garment_heatmaps = heatmaps[0, definition.channel_start:definition.channel_stop]
    points = _decode_points(garment_heatmaps, transform)
    points_by_id = {point['id']: point for point in points}
    suggestions = _measurement_paths(points_by_id, definition.measurement_paths)

    thigh_path = _thigh_width_path(points_by_id, garment)
    if thigh_path is not None:
        suggestions['thigh_width_half'] = thigh_path

    return points, suggestions


def _decode_points(heatmaps, transform):
    import numpy as np

    height, width = heatmaps.shape[1:]
    scaled_width, scaled_height, left, top = transform
    points = []
    for point_id, heatmap in enumerate(heatmaps, start=1):
        peak_index = int(np.argmax(heatmap))
        y, x = divmod(peak_index, width)
        score = float(heatmap[y, x])
        if not np.isfinite(score) or score < MIN_SCORE:
            continue

        offset_x = np.sign(heatmap[y, x + 1] - heatmap[y, x - 1]) * .25 if 1 < x < width - 1 else 0
        offset_y = np.sign(heatmap[y + 1, x] - heatmap[y - 1, x]) * .25 if 1 < y < height - 1 else 0
        image_x = ((x + offset_x) * INPUT_WIDTH / width - left) / scaled_width
        image_y = ((y + offset_y) * INPUT_HEIGHT / height - top) / scaled_height
        if 0 <= image_x <= 1 and 0 <= image_y <= 1:
            points.append({
                'id': point_id,
                'x': round(float(image_x), 6),
                'y': round(float(image_y), 6),
                'score': round(score, 4),
            })
    return points


def _measurement_paths(points_by_id, definitions):
    suggestions = {}
    for field, point_ids in definitions.items():
        if not all(point_id in points_by_id for point_id in point_ids):
            continue

        first = points_by_id[point_ids[0]]
        last = points_by_id[point_ids[-1]]
        distance_squared = (first['x'] - last['x']) ** 2 + (first['y'] - last['y']) ** 2
        if distance_squared >= MIN_PATH_DISTANCE_SQUARED:
            suggestions[field] = [
                [points_by_id[point_id]['x'], points_by_id[point_id]['y']]
                for point_id in point_ids
            ]
    return suggestions


def _thigh_width_path(points_by_id, garment):
    import numpy as np

    if garment == 'trousers':
        crotch_id, contour_ids = 9, (1, 4, 5, 6)
    elif garment == 'shorts':
        crotch_id, contour_ids = 7, (1, 4, 5)
    else:
        return None
    if not all(point_id in points_by_id for point_id in (1, 3, crotch_id)):
        return None

    waist_start = np.array([points_by_id[1]['x'], points_by_id[1]['y']])
    waist_end = np.array([points_by_id[3]['x'], points_by_id[3]['y']])
    crotch = np.array([points_by_id[crotch_id]['x'], points_by_id[crotch_id]['y']])
    waist_direction = waist_end - waist_start

    for first_id, second_id in zip(contour_ids, contour_ids[1:]):
        if first_id not in points_by_id or second_id not in points_by_id:
            continue

        segment_start = np.array([points_by_id[first_id]['x'], points_by_id[first_id]['y']])
        segment_end = np.array([points_by_id[second_id]['x'], points_by_id[second_id]['y']])
        denominator = _cross_2d(segment_end - segment_start, waist_direction)
        if abs(denominator) < PARALLEL_TOLERANCE:
            continue

        fraction = _cross_2d(crotch - segment_start, waist_direction) / denominator
        if 0 <= fraction <= 1:
            outer_point = segment_start + fraction * (segment_end - segment_start)
            if np.linalg.norm(outer_point - crotch) >= MIN_THIGH_WIDTH:
                return [outer_point.round(6).tolist(), crotch.tolist()]
            break
    return None


def _cross_2d(first, second):
    return first[0] * second[1] - first[1] * second[0]


def detect(raw, garment):
    if garment not in GARMENTS:
        raise AnalysisError('INVALID_GARMENT_TYPE', 400)
    started = perf_counter()
    tensor, transform = prepare_frame(raw)
    engine = session()
    try:
        heatmaps = engine.run(['heatmaps'], {'image': tensor})[0]
    except Exception as error:
        raise AnalysisError('LANDMARK_FAILED', 503) from error
    points, suggestions = decode(heatmaps, garment, transform)
    return {
        'model': MODEL_ID,
        'garment': garment,
        'points': points,
        'suggestions': suggestions,
        'elapsed_ms': round((perf_counter() - started) * 1000),
    }
