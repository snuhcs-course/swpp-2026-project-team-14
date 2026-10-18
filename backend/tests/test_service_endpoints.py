# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
from django.test import Client


def test_healthz_for_kubernetes_probes():
    response = Client().get("/healthz/", HTTP_HOST="localhost")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_unused_hello_endpoint_is_removed():
    assert Client().get("/api/hello/").status_code == 404


def test_service_endpoints_are_get_only():
    assert Client().post("/healthz/").status_code == 405
