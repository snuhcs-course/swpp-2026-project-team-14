# AI-generated with Codex and Claude Code (Dongkun Moon), 2026-11-25; serves stored cutouts with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
"""Owner-only transparent PNG of a saved garment for the wardrobe rail.

The cutout is normally made in the background right after saving (cutouts.schedule_cutout). A garment saved
before that, or whose cutout is stale, gets it made and stored here on its first request.
"""
from django.http import HttpResponse, JsonResponse
from django.shortcuts import get_object_or_404
from django.views.decorators.http import require_GET

from apps.users.auth import require_auth
from .analysis import AnalysisError
from .cutouts import HANGABLE, ensure_cutout
from .models import Garment


@require_GET
@require_auth
def image(request, item_id):
    # Ownership is checked before any stored file is read or any inference runs.
    garment = get_object_or_404(Garment, pk=item_id, user=request.user, saved=True)
    if garment.attributes.get('category') not in HANGABLE:
        return JsonResponse({'error': 'IMAGE_TARGET_UNCLEAR'}, status=422)
    try:
        result = ensure_cutout(garment, wait=12)
    except FileNotFoundError:
        return JsonResponse({'error': 'IMAGE_NOT_FOUND'}, status=404)
    except AnalysisError as error:
        return JsonResponse({'error': error.code}, status=error.status)
    response = HttpResponse(result, content_type='image/png')
    response['Cache-Control'] = 'private, no-store'
    response['Vary'] = 'Authorization'
    return response
