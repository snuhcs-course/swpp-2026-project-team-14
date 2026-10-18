# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-25, reviewed by Dongkun Moon
"""Public readiness/liveness endpoint."""

from django.http import JsonResponse
from django.views.decorators.http import require_GET


@require_GET
def health(request):
    """Kubernetes readiness/liveness probe (infra/apps/backend.yaml)."""
    return JsonResponse({"status": "ok"})
