from django.test import Client


def test_healthz_for_kubernetes_probes():
    response = Client().get("/healthz/", HTTP_HOST="localhost")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_hello_kept_from_original_app():
    response = Client().get("/api/hello/")
    assert response.status_code == 200
    assert response.json() == {"message": "ey yo"}


def test_service_endpoints_are_get_only():
    assert Client().post("/healthz/").status_code == 405
