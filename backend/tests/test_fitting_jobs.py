import threading
import time
import weakref
from concurrent.futures import ThreadPoolExecutor

import pytest

from apps.fittings.generation import GeneratedImage, GenerationError
from apps.fittings.jobs import Jobs, QueueFull


def make_jobs(runner=None, capacity=2):
    return Jobs(runner or (lambda *args: GeneratedImage(b"result", "0.06")), capacity=capacity, start=False)


def submit(jobs, owner="alice"):
    return jobs.submit(b"person", [("tops", b"shirt", "flat-lay", True)], owner)


def test_result_requires_account_and_capability_and_inputs_are_erased():
    jobs = make_jobs()
    job = submit(jobs)
    assert jobs.get(job.id, job.token, "bob") is None
    assert jobs.get(job.id, "wrong", "alice") is None
    assert jobs.get(job.id, "한글", "alice") is None
    assert jobs.run_next()
    assert jobs.get(job.id, job.token, "alice") is job
    assert job.output == b"result" and job.cost_usd == "0.06"
    assert jobs.status(job)["inputs_deleted"]
    jobs.delete(job)
    assert job.output is None and not jobs.jobs


def test_provider_error_is_not_retried_and_clears_inputs():
    calls = []
    def run(*args):
        calls.append(1)
        raise GenerationError("FITTING_CREDITS_REQUIRED")
    jobs = make_jobs(run)
    job = submit(jobs)
    jobs.run_next()
    assert calls == [1]
    assert job.state == "failed" and job.error == "FITTING_CREDITS_REQUIRED"
    assert job.person is None and not job.garments and job.output is None


def test_unknown_exception_details_and_frames_are_not_retained():
    class Buffer:
        pass
    references = []
    def run(*args):
        private_buffer = Buffer()
        references.append(weakref.ref(private_buffer))
        raise RuntimeError("private photo and credential details")
    jobs = make_jobs(run)
    job = submit(jobs)
    jobs.run_next()
    assert job.error == "FITTING_GENERATION_FAILED"
    assert references[0]() is None and job.person is None


def test_queued_cancellation_never_calls_paid_provider():
    calls = []
    jobs = make_jobs(lambda *args: calls.append(1))
    job = submit(jobs)
    jobs.delete(job)
    assert not jobs.run_next() and not calls
    assert job.person is None and not job.garments


def test_running_cancellation_does_not_corrupt_active_inputs_or_restore_result():
    started, release = threading.Event(), threading.Event()
    def run(person, garments):
        started.set()
        assert release.wait(2)
        assert person == b"person" and len(garments) == 1
        return GeneratedImage(b"late")
    jobs = make_jobs(run)
    job = submit(jobs)
    thread = threading.Thread(target=jobs.run_next)
    thread.start()
    try:
        assert started.wait(1)
        jobs.delete(job)
        assert job.person is None and not job.garments
    finally:
        release.set()
        thread.join(3)
    assert not jobs.jobs and job.output is None and job.state == "cancelled"


def test_capacity_and_expiry_bound_queued_inputs_and_unclaimed_results():
    jobs = make_jobs(capacity=1)
    first = submit(jobs)
    with pytest.raises(QueueFull):submit(jobs)
    jobs.run_next()
    second = submit(jobs)
    jobs.run_next()
    with pytest.raises(QueueFull):submit(jobs)
    first.finished -= 1000
    second.finished -= 1000
    jobs.purge()
    assert not jobs.jobs and first.output is None and second.output is None


def test_ten_concurrent_jobs_are_isolated_and_obey_provider_concurrency_limit():
    lock = threading.Lock()
    active, peak, calls = 0, 0, []
    def run(person, garments):
        nonlocal active, peak
        with lock:
            active += 1
            peak = max(peak, active)
            calls.append(person)
        time.sleep(0.02)
        with lock:active -= 1
        return GeneratedImage(person + b" result", "0.06")
    jobs = Jobs(run, concurrency=2, capacity=10)
    try:
        def upload(index):
            owner = str(index)
            return jobs.submit(owner.encode(), [("tops", b"shirt", "flat-lay", True)], owner)
        with ThreadPoolExecutor(max_workers=10) as pool:
            uploaded = list(pool.map(upload, range(10)))
        deadline = time.monotonic() + 5
        while any(j.state not in ("completed", "failed") for j in uploaded) and time.monotonic() < deadline:
            time.sleep(0.01)
        assert 1 < peak <= 2 and len(calls) == 10
        for job in uploaded:
            assert job.state == "completed" and job.output == job.owner.encode() + b" result"
            assert jobs.get(job.id, job.token, "other") is None
            assert jobs.status(job)["inputs_deleted"]
            jobs.delete(job)
        assert not jobs.jobs
    finally:jobs.close()


def test_thirty_sequential_jobs_release_memory_and_remain_usable():
    jobs = make_jobs()
    for _ in range(30):
        job = submit(jobs)
        jobs.run_next()
        assert job.state == "completed" and jobs.status(job)["inputs_deleted"]
        jobs.delete(job)
        assert not jobs.jobs and job.output is None


def test_background_expiry_removes_abandoned_results_without_a_client_request():
    jobs = Jobs(lambda *args: GeneratedImage(b"unclaimed"), ttl=0.03)
    try:
        job = submit(jobs)
        deadline = time.monotonic() + 3
        while job.id in jobs.jobs and time.monotonic() < deadline:
            time.sleep(0.01)
        assert job.id not in jobs.jobs
        assert job.person is None and not job.garments and job.output is None
    finally:
        jobs.close()


def test_expired_queued_photos_never_reach_the_provider_and_close_rejects_new_jobs():
    started, release = threading.Event(), threading.Event()
    calls = []
    def run(person, garments):
        calls.append(person)
        started.set()
        assert release.wait(3)
        return GeneratedImage(b"late")
    jobs = Jobs(run, concurrency=1, ttl=0.05)
    try:
        first = submit(jobs)
        assert started.wait(1)
        queued = jobs.submit(b"queued-private-photo", [("tops", b"shirt", "flat-lay", True)], "alice")
        deadline = time.monotonic() + 2
        while jobs.jobs and time.monotonic() < deadline:
            time.sleep(0.01)
        assert not jobs.jobs and queued.person is None and not queued.garments
        assert calls == [b"person"]
    finally:
        jobs.close()
        release.set()
    with pytest.raises(QueueFull):
        submit(jobs)
