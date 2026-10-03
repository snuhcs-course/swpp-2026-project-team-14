"""Download pinned GarmentIQ weights and export once; run in a separate torch environment."""
import hashlib
import importlib.util
import json
import shutil
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / '.local/model-research/garmentiq'
DEST = ROOT / '.local/models/garment-landmarks'
REVISION = '5f02016e9ad3a4aa171fa9199423a437170f5afe'
SOURCE_REVISION = '6eba6d65f462647b48e9eed24440d609e9e671d6'
WEIGHTS_SHA = '5b29ada40632cb5ce1aaa38e4896054329c42d0f6c6649a5b9d0b53e41ee04f6'
SOURCE_SHA = 'd7e9ad5c5f170619033bb271406c28fb3adbe86de51018156e0d02a4a5bd813a'
FOREGROUND_SHA = '8d10d2f3bb75ae3b6d527c77944fc5e7dcd94b29809d47a739a7a728a912b491'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download(name, url, expected=None):
    path = BUILD / name
    if not path.exists() or (expected and digest(path) != expected):
        temporary = path.with_suffix(path.suffix + '.part')
        with urlopen(url, timeout=120) as response, temporary.open('wb') as target:
            while chunk := response.read(1024 * 1024):
                target.write(chunk)
        if expected and digest(temporary) != expected:
            raise ValueError(f'Checksum mismatch: {name}')
        temporary.replace(path)
    return path


def prepare_foreground():
    BUILD.mkdir(parents=True, exist_ok=True)
    DEST.mkdir(parents=True, exist_ok=True)
    target = DEST / 'u2net.onnx'
    if not target.exists() or digest(target) != FOREGROUND_SHA:
        weights = download('u2net.onnx', 'https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net.onnx', FOREGROUND_SHA)
        shutil.copyfile(weights, target)
    license_file = download('U2NET-LICENSE', 'https://raw.githubusercontent.com/xuebinqin/U-2-Net/master/LICENSE')
    shutil.copyfile(license_file, DEST / 'U2NET-LICENSE')
    print(f'Foreground model ready: {target}')


def main():
    import torch
    import onnx
    BUILD.mkdir(parents=True, exist_ok=True)
    DEST.mkdir(parents=True, exist_ok=True)
    source = download('model_definition.py',
                      f'https://raw.githubusercontent.com/lygitdata/GarmentIQ/{SOURCE_REVISION}/src/garmentiq/landmark/detection/model_definition.py', SOURCE_SHA)
    weights = download('hrnet.pth', f'https://huggingface.co/lygitdata/garmentiq/resolve/{REVISION}/hrnet.pth', WEIGHTS_SHA)
    license_file = download('LICENSE', f'https://raw.githubusercontent.com/lygitdata/GarmentIQ/{SOURCE_REVISION}/LICENSE')
    spec = importlib.util.spec_from_file_location('garmentiq_model', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    torch.set_num_threads(2)
    checkpoint = torch.load(weights, map_location='cpu', weights_only=True)
    state = checkpoint.get('state_dict', checkpoint)
    model = module.PoseHighResolutionNet().eval()
    model.load_state_dict({key.removeprefix('module.'): value for key, value in state.items()}, strict=True)
    del checkpoint, state
    temporary = DEST / 'hrnet.pending.onnx'
    with torch.inference_mode():
        torch.onnx.export(model, torch.zeros(1, 3, 384, 288), temporary,
                          input_names=['image'], output_names=['heatmaps'], opset_version=17, dynamo=False)
    onnx.checker.check_model(str(temporary))
    manifest = {'model': 'lygitdata/garmentiq', 'revision': REVISION,
                'source_revision': SOURCE_REVISION, 'weights_sha256': WEIGHTS_SHA,
                'sha256': digest(temporary), 'input': [1, 3, 384, 288], 'license': 'MIT'}
    temporary.replace(DEST / 'hrnet.onnx')
    (DEST / 'LICENSE').write_bytes(license_file.read_bytes())
    (DEST / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'Ready: {DEST}')
    prepare_foreground()


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--benchmark-image', type=Path, help='Benchmark the running local API with a test image instead of exporting.')
    parser.add_argument('--foreground-only', action='store_true', help='Download U2Net without re-exporting HRNet.')
    parser.add_argument('--remove-background', action='store_true', help='Include U2Net when benchmarking.')
    args = parser.parse_args()
    if args.foreground_only:
        prepare_foreground()
    elif args.benchmark_image is None:
        main()
    else:
        from io import BytesIO
        from threading import Event, Thread
        from time import perf_counter
        from urllib.request import Request
        from PIL import Image, ImageOps
        import dev
        process = dev.managed_backend()
        if process is None:
            raise SystemExit('Start the local backend first.')
        image = ImageOps.exif_transpose(Image.open(args.benchmark_image)).convert('RGB')
        image.thumbnail((768, 768))
        output = BytesIO()
        image.save(output, 'JPEG', quality=85)
        stopped, peak = Event(), [0]

        def sample_memory():
            while not stopped.is_set():
                # Windows venv python.exe launches a child interpreter; include the whole server tree.
                usage = [p.memory_info() for p in [process, *process.children(recursive=True)]]
                peak[0] = max(peak[0], sum(getattr(m, 'peak_wset', m.rss) for m in usage))
                stopped.wait(.01)

        sampler = Thread(target=sample_memory)
        sampler.start()
        times = []
        try:
            for _ in range(12):
                started = perf_counter()
                background = 'remove' if args.remove_background else 'keep'
                request = Request(f'http://127.0.0.1:8001/api/wardrobe/landmarks/?garment=short_sleeve_top&background={background}',
                                  output.getvalue(), {'Content-Type': 'image/jpeg'})
                with urlopen(request, timeout=30) as response:
                    result = json.load(response)
                times.append(round((perf_counter() - started) * 1000, 2))
                if 'suggestions' not in result:
                    raise ValueError('Unexpected landmark response')
        finally:
            stopped.set()
            sampler.join()
        print(json.dumps({'requests_ms': times, 'server_tree_peak_bytes': peak[0]}, indent=2))
