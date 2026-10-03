"""GarmentIQ HRNet inference. Predict image points; ARCore remains the cm reference."""
import hashlib
import base64
import json
from io import BytesIO
from pathlib import Path
from threading import Lock
from time import perf_counter

from django.conf import settings
from PIL import Image, ImageOps, UnidentifiedImageError

from .analysis import AnalysisError

MODEL_ID = 'lygitdata/garmentiq'
MODEL_REVISION = '5f02016e9ad3a4aa171fa9199423a437170f5afe'
INPUT_WIDTH, INPUT_HEIGHT = 288, 384
MAX_FRAME_BYTES = 1024 * 1024
MIN_SCORE = 0.4  # Heatmap score, not a calibrated probability.
# DeepFashion2 class slices (zero-based) and landmark paths (one-based).
# Endpoints: shoulder / underarm / back neck / hem / outer cuff / inner cuff.
# Reference diagram: github.com/switchablenorms/DeepFashion2/blob/master/images/cls.jpg
GARMENTS = {
    'short_sleeve_top': (0, 25, {'shoulder_width': (7, 25), 'chest_width_half': (12, 20),
                              'armhole_straight': (7, 12), 'sleeve_length': (25, 24, 23),
                              'total_length': (1, 16), 'hem_width_half': (15, 17), 'cuff_width_half': (23, 22)}),
    'long_sleeve_top': (25, 58, {'shoulder_width': (7, 33), 'chest_width_half': (16, 24),
                               'armhole_straight': (7, 16), 'sleeve_length': (33, 32, 31, 30, 29),
                               'total_length': (1, 20), 'hem_width_half': (19, 21), 'cuff_width_half': (29, 28)}),
    'short_sleeve_outerwear': (58, 89, {'shoulder_width': (7, 25), 'chest_width_half': (12, 20),
                                      'armhole_straight': (7, 12), 'sleeve_length': (25, 24, 23),
                                      'total_length': (1, 16), 'hem_width_half': (15, 17), 'cuff_width_half': (23, 22)}),
    'long_sleeve_outerwear': (89, 128, {'shoulder_width': (7, 33), 'chest_width_half': (16, 24),
                                      'armhole_straight': (7, 16), 'sleeve_length': (33, 32, 31, 30, 29),
                                      'total_length': (1, 20), 'hem_width_half': (19, 21), 'cuff_width_half': (29, 28)}),
    'trousers': (168, 182, {'waist_width_half': (1, 3), 'hip_width_half': (4, 14),
                           'total_length': (1, 4, 5, 6), 'rise_front': (2, 9),
                           'inseam': (9, 8, 7), 'hem_opening': (6, 7)}),
    'shorts': (158, 168, {'waist_width_half': (1, 3), 'hip_width_half': (4, 10),
                        'total_length': (1, 4, 5), 'rise_front': (2, 7),
                        'inseam': (7, 6), 'hem_opening': (5, 6)}),
    'skirt': (182, 190, {'waist_width_half': (1, 3), 'hip_width_half': (4, 8),
                       'total_length': (1, 4, 5)}),
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
    # Fit the complete viewport, retaining aspect ratio and the inverse coordinate transform.
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
    import numpy as np
    begin, end, definitions = GARMENTS[garment]
    maps = heatmaps[0, begin:end]
    height, width = maps.shape[1:]
    scaled_w, scaled_h, left, top = transform
    points = []
    for index, heatmap in enumerate(maps, start=1):
        flat = int(np.argmax(heatmap))
        y, x = divmod(flat, width)
        score = float(heatmap[y, x])
        if not np.isfinite(score) or score < MIN_SCORE:
            continue
        dx = np.sign(heatmap[y, x + 1] - heatmap[y, x - 1]) * .25 if 1 < x < width - 1 else 0
        dy = np.sign(heatmap[y + 1, x] - heatmap[y - 1, x]) * .25 if 1 < y < height - 1 else 0
        u = ((x + dx) * INPUT_WIDTH / width - left) / scaled_w
        v = ((y + dy) * INPUT_HEIGHT / height - top) / scaled_h
        if 0 <= u <= 1 and 0 <= v <= 1:
            points.append({'id': index, 'x': round(float(u), 6), 'y': round(float(v), 6), 'score': round(score, 4)})
    by_id = {point['id']: point for point in points}
    suggestions = {}
    for field, path in definitions.items():
        if all(index in by_id for index in path):
            a, b = by_id[path[0]], by_id[path[-1]]
            if (a['x'] - b['x']) ** 2 + (a['y'] - b['y']) ** 2 >= .0004:
                suggestions[field] = [[by_id[index]['x'], by_id[index]['y']] for index in path]
    # Thigh: intersect the outer leg contour at crotch level, parallel to the waistband.
    # This is a derived image point, not an extra keypoint predicted by HRNet.
    if garment in ('trousers', 'shorts'):
        crotch, contour = (9, (1, 4, 5, 6)) if garment == 'trousers' else (7, (1, 4, 5))
        if all(index in by_id for index in (1, 3, crotch)):
            a = np.array([by_id[1]['x'], by_id[1]['y']])
            b = np.array([by_id[3]['x'], by_id[3]['y']])
            c = np.array([by_id[crotch]['x'], by_id[crotch]['y']])
            direction = b - a
            cross = lambda u, v: u[0] * v[1] - u[1] * v[0]
            for first, second in zip(contour, contour[1:]):
                if first not in by_id or second not in by_id:
                    continue
                p = np.array([by_id[first]['x'], by_id[first]['y']])
                q = np.array([by_id[second]['x'], by_id[second]['y']])
                denominator = cross(q - p, direction)
                if abs(denominator) < 1e-6:
                    continue
                t = cross(c - p, direction) / denominator
                if 0 <= t <= 1:
                    outer = p + t * (q - p)
                    if np.linalg.norm(outer - c) >= .02:
                        suggestions['thigh_width_half'] = [outer.round(6).tolist(), c.tolist()]
                    break
    return points, suggestions


def restore_viewport(points, suggestions, box, size):
    left, top, right, bottom = box
    width, height = size
    def restore(point):
        return [round((left + point[0] * (right - left)) / width, 6),
                round((top + point[1] * (bottom - top)) / height, 6)]
    restored = []
    for point in points:
        x, y = restore((point['x'], point['y']))
        restored.append(dict(point, x=x, y=y))
    return restored, {key: [restore(point) for point in path] for key, path in suggestions.items()}


def detect(raw, garment, remove_background=False):
    if garment not in GARMENTS:
        raise AnalysisError('INVALID_GARMENT_TYPE', 400)
    started = perf_counter()
    image = read_frame(raw)
    original = image
    size = image.size
    box, removed = (0, 0, *size), False
    if remove_background:
        from .foreground import foreground_crop
        image, box, removed = foreground_crop(image)
    tensor, transform = image_tensor(image)
    engine = session()
    try:
        heatmaps = engine.run(['heatmaps'], {'image': tensor})[0]
    except Exception as error:
        raise AnalysisError('LANDMARK_FAILED', 503) from error
    points, suggestions = decode(heatmaps, garment, transform)
    points, suggestions = restore_viewport(points, suggestions, box, size)
    fallback_fields = []
    expected = set(GARMENTS[garment][2])
    if garment in ('trousers', 'shorts'):
        expected.add('thigh_width_half')
    if removed and expected - set(suggestions):
        # Segmentation can erase cuffs/edges. Recover missing paths from the original frame.
        original_tensor, original_transform = image_tensor(original)
        try:
            original_maps = engine.run(['heatmaps'], {'image': original_tensor})[0]
            _, original_suggestions = decode(original_maps, garment, original_transform)
            for field, path in original_suggestions.items():
                if field not in suggestions:
                    suggestions[field] = path
                    fallback_fields.append(field)
        except Exception:
            pass  # Retain the successfully detected crop paths if optional recovery fails.
    preview = None
    if removed:
        # Keep the viewport's dimensions and origin for the Android overlay and AR rays.
        canvas = Image.new('RGB', size, 'white')
        canvas.paste(image, (box[0], box[1]))
        canvas.thumbnail((768, 768))
        output = BytesIO()
        canvas.save(output, 'JPEG', quality=80)
        preview = base64.b64encode(output.getvalue()).decode('ascii')
    return {'model': MODEL_ID, 'garment': garment, 'points': points, 'suggestions': suggestions,
            'background_removed': removed, 'preview_jpeg': preview, 'fallback_fields': fallback_fields,
            'elapsed_ms': round((perf_counter() - started) * 1000)}
