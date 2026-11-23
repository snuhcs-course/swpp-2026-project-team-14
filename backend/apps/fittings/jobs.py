"""Bounded, in-memory API jobs for the backend's single Gunicorn process."""
from collections import deque
from dataclasses import dataclass, field
import hmac
import statistics
import logging
import secrets
import threading
import time
import traceback
import uuid

from .generation import GenerationError


class QueueFull(Exception):
    pass


DEFAULT_EXPECTED_SECONDS = 40.0  # before any generation finished in this process
RECENT_DURATIONS = 20


def progress_of(state: str, running_seconds: float, expected: float) -> float:
    """0–1 for the app's progress bar: a small step while waiting, then the share of the usual generation
    time, held below 0.95 until the image is really there (it is an estimate, not a measurement)."""
    if state == "completed":
        return 1.0
    if state == "running":
        return round(min(0.95, 0.1 + 0.85 * running_seconds / max(expected, 1.0)), 3)
    return 0.05 if state == "queued" else 0.0


@dataclass
class Job:
    person: bytes | None
    garments: list
    owner: str
    token: str = field(default_factory=lambda: secrets.token_urlsafe(32))
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    created: float = field(default_factory=time.monotonic)
    state: str = "queued"
    output: bytes | None = None
    started: float | None = None
    finished: float | None = None
    error: str | None = None
    cost_usd: str | None = None
    cancelled: bool = False

    def clear_inputs(self):
        self.person = None
        self.garments.clear()


class Jobs:
    def __init__(self, runner, concurrency=2, capacity=20, ttl=900, start=True):
        self.runner, self.concurrency, self.capacity, self.ttl = runner, concurrency, capacity, ttl
        self.jobs = {}
        self.durations = deque(maxlen=RECENT_DURATIONS)  # generation seconds of recent completed jobs
        self.condition = threading.Condition(threading.RLock())
        self.stopped = threading.Event()
        if start:
            for index in range(concurrency):
                threading.Thread(target=self._work, daemon=True, name=f"fitting-api-{index}").start()
            threading.Thread(target=self._reap, daemon=True, name="fitting-expiry").start()

    def purge(self):
        with self.condition:
            now = time.monotonic()
            for key, job in list(self.jobs.items()):
                if now - (job.finished or job.created) > self.ttl:
                    job.cancelled = True
                    job.clear_inputs()
                    job.output = None
                    del self.jobs[key]

    def submit(self, person, garments, owner):
        with self.condition:
            self.purge()
            if self.stopped.is_set() or len(self.jobs) >= self.capacity * 2 or sum(
                j.state in ("queued", "running") for j in self.jobs.values()
            ) >= self.capacity:
                raise QueueFull()
            job = Job(person, list(garments), owner)
            self.jobs[job.id] = job
            self.condition.notify()
            return job

    def get(self, key, token, owner):
        with self.condition:
            self.purge()
            job = self.jobs.get(str(key))
            return job if (job and job.owner == owner and isinstance(token, str)
                           and token.isascii() and hmac.compare_digest(job.token, token)) else None

    def expected_seconds(self) -> float:
        with self.condition:
            return statistics.median(self.durations) if self.durations else DEFAULT_EXPECTED_SECONDS

    def status(self, job):
        with self.condition:
            waiting = [j.id for j in self.jobs.values() if j.state in ("queued", "running")]
            expected = self.expected_seconds()
            running = (job.finished or time.monotonic()) - job.started if job.started is not None else 0.0
            stage = {"queued": "waiting", "running": "generating", "completed": "done"}.get(job.state, job.state)
            return {"id": job.id, "status": job.state, "stage": stage,
                    "progress": progress_of(job.state, running, expected),
                    "expected_seconds": round(expected, 1),
                    "queue_position": waiting.index(job.id) if job.id in waiting else 0,
                    "elapsed_seconds": round((job.finished or time.monotonic()) - job.created, 2),
                    "generation_seconds": round(job.finished - job.started, 2)
                    if job.finished is not None and job.started is not None else None,
                    "inputs_deleted": job.person is None and not job.garments,
                    "cost_usd": job.cost_usd, "error": job.error}

    def delete(self, job):
        with self.condition:
            job.cancelled = True
            job.clear_inputs()
            job.output = None
            self.jobs.pop(job.id, None)

    def run_next(self):
        with self.condition:
            self.purge()
            job = next((j for j in self.jobs.values() if j.state == "queued"), None)
            if not job or self.stopped.is_set():
                return False
            job.state, job.started = "running", time.monotonic()
            person, garments = job.person, list(job.garments)
        result, code = None, None
        try:
            result = self.runner(person, garments)
        except Exception as error:
            code = str(error) if isinstance(error, GenerationError) else "FITTING_GENERATION_FAILED"
            traceback.clear_frames(error.__traceback__)
            logging.getLogger(__name__).warning("Fitting API job failed: %s", code)
        finally:
            person = None
            garments.clear()
            with self.condition:
                job.clear_inputs()
                job.finished, job.error = time.monotonic(), code
                if result:
                    job.cost_usd = result.cost_usd
                job.output = result.image if result and not job.cancelled else None
                job.state = "cancelled" if job.cancelled else "failed" if code else "completed"
                if job.state == "completed":
                    self.durations.append(job.finished - job.started)
        return True

    def _work(self):
        while not self.stopped.is_set():
            if not self.run_next():
                with self.condition:
                    if not any(j.state == "queued" for j in self.jobs.values()):
                        self.condition.wait(timeout=1)

    def _reap(self):
        while not self.stopped.wait(min(self.ttl, 10)):
            self.purge()

    def close(self):
        self.stopped.set()
        with self.condition:
            for job in list(self.jobs.values()):
                self.delete(job)
            self.condition.notify_all()
