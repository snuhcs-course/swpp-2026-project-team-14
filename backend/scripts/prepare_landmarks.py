"""Download pinned GarmentIQ weights and export once; run in a separate torch environment."""
import hashlib
import importlib.util
import json
import shutil
from pathlib import Path
from urllib.request import urlopen

BACKEND = Path(__file__).resolve().parent.parent
BUILD = BACKEND / 'private/model-export/garmentiq'
DEST = BACKEND / 'wardrobe/garment-landmarks'
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
    parser.add_argument('--foreground-only', action='store_true', help='Download U2Net without re-exporting HRNet.')
    args = parser.parse_args()
    if args.foreground_only:
        prepare_foreground()
    else:
        main()
