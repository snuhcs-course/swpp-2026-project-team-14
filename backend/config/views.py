"""Service endpoints kept from the team's original deployment app (backend/app.py)."""

from django.http import JsonResponse
from django.views.decorators.http import require_GET


@require_GET
def hello(request):
    return JsonResponse({"message": "ey yo"})


@require_GET
def health(request):
    """Kubernetes readiness/liveness probe (infra/apps/backend.yaml)."""
    return JsonResponse({"status": "ok"})
