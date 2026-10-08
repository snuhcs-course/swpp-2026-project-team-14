# AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong, Kihwan Kim), 2026-09-24, reviewed by Dongkun Moon, Hyeon U Jeong and Kihwan Kim
FROM python:3.12-slim AS wardrobe-model

WORKDIR /build
RUN pip install --no-cache-dir torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir onnx==1.19.0
COPY scripts/prepare_landmarks.py ./scripts/prepare_landmarks.py
# Download pinned source weights and export ONNX only while building the image.
RUN python scripts/prepare_landmarks.py \
    && chmod 755 wardrobe wardrobe/garment-landmarks \
    && chmod 644 wardrobe/garment-landmarks/*

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    # gunicorn and MediaPipe write small runtime files under $HOME; "/" is not writable for uid 10001
    HOME=/tmp \
    DJANGO_SETTINGS_MODULE=config.settings \
    DJANGO_ALLOWED_HOSTS=localhost,127.0.0.1,backend

# OpenCV and MediaPipe's native library need these even without a display
# (without libEGL, loading the pose model fails with "libEGL.so.1: cannot open shared object file").
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libegl1 libgles2 default-libmysqlclient-dev build-essential pkg-config \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# MediaPipe pose model (Apache 2.0), pinned by checksum.
ADD --checksum=sha256:64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b \
    https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task \
    /app/models/pose_landmarker_heavy.task
RUN chmod 644 /app/models/pose_landmarker_heavy.task

COPY manage.py .
COPY config ./config
COPY apps ./apps
COPY body_analysis ./body_analysis
COPY --from=wardrobe-model /build/wardrobe/garment-landmarks /app/wardrobe/garment-landmarks

USER 10001:10001

EXPOSE 8000

# One worker: the pose model is loaded once per process (~280 MB) and analyses are serialised.
CMD ["gunicorn", "config.wsgi:application", "--bind", "0.0.0.0:8000", "--workers", "1", "--threads", "2", "--timeout", "60", "--access-logfile", "-", "--error-logfile", "-"]
