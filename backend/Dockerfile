FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

USER 10001:10001

EXPOSE 8000

CMD ["gunicorn", "app:application", "--bind", "0.0.0.0:8000", "--workers", "1", "--threads", "2", "--access-logfile", "-", "--error-logfile", "-"]
