"""Download pinned segmentation weights once; never during an API request."""
import hashlib
from pathlib import Path
import tempfile
from urllib.request import urlopen

from django.conf import settings
from django.core.management.base import BaseCommand, CommandError
from apps.wardrobe.worn import MODEL_SHA256, MODEL_URL


class Command(BaseCommand):
    help = 'Download and verify the pinned worn-clothing ONNX model.'

    def handle(self, *args, **options):
        path = Path(settings.WORN_SEGMENTATION_MODEL)
        if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == MODEL_SHA256:
            self.stdout.write('Worn segmentation model already verified.')
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=path.parent) as directory:
            temporary = Path(directory) / 'model.onnx'
            with urlopen(MODEL_URL, timeout=120) as response, temporary.open('wb') as output:
                while chunk := response.read(1024 * 1024): output.write(chunk)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != MODEL_SHA256:
                raise CommandError('Model checksum mismatch; existing model was not changed.')
            temporary.replace(path)
        self.stdout.write('Worn segmentation model downloaded and verified.')
