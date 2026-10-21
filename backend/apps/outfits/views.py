# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
"""Outfit recommendation from the user's own wardrobe, the 코디 table, and the weather.

POST /api/outfits/recommend/          outfits from the saved garments (docs/recommendation/api.md)
GET  /api/outfits/                    saved outfits (코디북)
POST /api/outfits/                    save an outfit made from garment ids (user or chat)
GET|PUT /api/outfits/<id>/            one outfit; PUT {saved, name}
POST /api/outfits/<id>/feedback/      like / dislike; returns the updated taste profile
GET  /api/weather/?lat=&lon=          current weather (Open-Meteo)
"""

from __future__ import annotations

import json
import math
import uuid
from functools import lru_cache

from django.db import transaction
from django.http import JsonResponse
from django.shortcuts import get_object_or_404
from django.views.decorators.http import require_GET, require_http_methods, require_POST
from django.utils import timezone

from apps.body_profiles.profile import body_from_request
from apps.wardrobe.models import Garment
from apps.wardrobe.editor import validate_record
from apps.users.auth import require_auth
from apps.users.models import User
from apps.users.pagination import account_page
from recommendation import preference, season, weather
from recommendation.catalog import Catalog
from recommendation.labeling import label
from recommendation.search import RecommendError, Request, recommend
from recommendation.vocab import PROFILE_STYLES, WARDROBE_CATEGORY
from recommendation.wardrobe import to_item

from .models import Outfit, OutfitItem

MAX_REQUEST_BYTES = 64 * 1024
PREF_FITS = ("slim", "regular", "loose")
SLOT_CODE = {slot: code for code, slot in WARDROBE_CATEGORY.items()}  # 상의 -> top
MAX_COUNT = 5
MAX_SEEN = 100
MAX_PREVIEW_BYTES = 1024 * 1024


SLOT_ORDER = ("outerwear", "top", "bottom")


class TooLarge(Exception):
    pass


class NotJson(Exception):
    pass


@lru_cache(maxsize=1)
def models() -> Catalog:
    """PMI statistics and the masked transformer (backend/recommendation/models), loaded once."""
    return Catalog.load_models()


def _json(data: dict, status: int = 200) -> JsonResponse:
    response = JsonResponse(data, status=status, json_dumps_params={"ensure_ascii": False})
    response["Cache-Control"] = "no-store"
    return response


def _error(code: str, status: int, hint: str = "") -> JsonResponse:
    return _json({"error": code, **({"hint": hint} if hint else {})}, status)


def _read(request, limit=MAX_REQUEST_BYTES) -> dict:
    if request.content_type != "application/json":
        raise NotJson()
    raw = request.read(limit + 1)
    if len(raw) > limit:
        raise TooLarge()
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("Object required")
    return data


def _number(value, low: float, high: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError("Invalid number")
    return float(value)


def _strings(value, limit: int, length: int, allowed=None) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list) or len(value) > limit or any(
            not isinstance(v, str) or len(v) > length or (allowed is not None and v not in allowed) for v in value):
        raise ValueError("Invalid list")
    return list(dict.fromkeys(value))


def _preference(raw) -> dict:
    """The taste profile kept on the device (recommendation/preference.py)."""
    if raw is None:
        return preference.empty()
    if not isinstance(raw, dict):
        raise ValueError("Invalid preference")
    weights = raw.get("weights") or {}
    if not isinstance(weights, dict) or len(weights) > 1000 or any(
            not isinstance(k, str) or len(k) > 80 for k in weights):
        raise ValueError("Invalid preference")
    return {
        "weights": {k: max(-preference.CLIP, min(preference.CLIP, _number(v, -100, 100))) for k, v in weights.items()},
        "excluded": _strings(raw.get("excluded"), 500, 100),
        "liked": _strings(raw.get("liked"), 200, 100),
        "updated_at": raw.get("updated_at") if isinstance(raw.get("updated_at"), int) else None,
    }


def _weather(data: dict) -> tuple[float, str | None, dict]:
    """(temperature for the season band, "비"/"눈"/None, context) from the /api/weather/ result or a plain temp."""
    w = data.get("weather")
    if isinstance(w, dict):
        temp = _number(w.get("apparent", w.get("temp")), -40, 50)
        wet = "눈" if w.get("snow") is True else "비" if w.get("rain") is True else None
        label_ = w.get("label") if isinstance(w.get("label"), str) else None
        return temp, wet, {"temp": temp, "label": (label_ or "")[:20], "wet": wet}
    temp = _number(data.get("temp"), -40, 50)
    return temp, None, {"temp": temp}


def _garment_item(g: Garment, body):
    """Wardrobe garment -> recommender Item. Garments saved before labels existed get them computed
    on the fly from their dimensions (not stored: the user has not reviewed them)."""
    labels = g.labels or label(g.attributes, g.dimensions, body)
    return to_item({"id": str(g.id), "attributes": g.attributes, "dimensions": g.dimensions, "labels": labels,
                    "image": f"/api/wardrobe/items/{g.id}/image/"})


def serialize(outfit: Outfit) -> dict:
    items = sorted(outfit.items.all(), key=lambda i: SLOT_ORDER.index(i.slot))
    return {
        "id": str(outfit.id), "source": outfit.source, "saved": outfit.saved, "name": outfit.name,
        "key": outfit.key, "score": outfit.score, "components": outfit.components, "reasons": outfit.reasons,
        "context": outfit.context, "feedback": outfit.feedback, "created_at": outfit.created_at.isoformat(),
        "items": [{
            "garment_id": str(i.garment_id), "slot": i.slot, "name": i.garment.attributes.get("name", ""),
            "colors": i.garment.attributes.get("colors", []), "image_url": f"/api/wardrobe/items/{i.garment_id}/image/",
            "note": i.note,
        } for i in items],
    }


def _create(source: str, garments: list[tuple[str, Garment, str]], **fields) -> Outfit:
    """garments: (slot code, garment, note)."""
    outfit = Outfit.objects.create(source=source, key="+".join(sorted(f"w:{g.id}" for _, g, _ in garments)), **fields)
    OutfitItem.objects.bulk_create([OutfitItem(outfit=outfit, garment=g, slot=slot, note=note[:200])
                                    for slot, g, note in garments])
    return outfit


def _calculate(data, garments):
    body = body_from_request(None, data)
    profile = data["body"]
    pref_fit = data.get("pref_fit", profile.get("preferred_fit", "regular"))
    if pref_fit not in PREF_FITS:
        raise ValueError("Invalid pref_fit")
    styles = _strings(data.get("styles", profile.get("preferred_styles")), 6, 20, PROFILE_STYLES)
    temp, wet, weather_context = _weather(data)
    count = int(_number(data.get("count", 3), 1, MAX_COUNT))
    seen_keys = _strings(data.get("seen_keys"), MAX_SEEN, 400)
    occasion = data.get("occasion")
    if occasion is not None and (not isinstance(occasion, str) or len(occasion) > 40):
        raise ValueError("Invalid occasion")
    anchor = data.get("anchor_id")
    if anchor is not None and not isinstance(anchor, str):
        raise ValueError("Invalid anchor")
    taste = _preference(data.get("preference"))
    items = [i for i in (_garment_item(g, body) for g in garments.values()) if i]
    req = Request(body=body, pref_fit=pref_fit, styles=styles, temp=temp, wet=wet, mode="wardrobe",
                  anchor=f"w:{anchor}" if anchor else None, wardrobe=items, preference=taste,
                  seen_outfits=seen_keys, count=count)
    result = recommend(models(), req)
    context = {**weather_context, "band": result["band"]["label"], "pref_fit": pref_fit, "styles": styles,
               **({"occasion": occasion} if occasion else {})}
    return result, context


@require_POST
@require_auth
def recommend_view(request):
    try:
        data = _read(request)
        garments = {str(g.id): g for g in Garment.objects.filter(user=request.user, saved=True)}
        result, context = _calculate(data, garments)
    except TooLarge:
        return _error("BODY_TOO_LARGE", 413)
    except NotJson:
        return _error("JSON_REQUIRED", 415)
    except RecommendError as error:
        return _error(error.code, 422, str(error))
    except (ValueError, TypeError, KeyError, UnicodeDecodeError, OverflowError) as error:
        return _error("INVALID_REQUEST", 400, str(error))
    outfits = []
    with transaction.atomic():
        User.objects.select_for_update().get(pk=request.user.pk)
        garments = {str(g.id): g for g in Garment.objects.filter(user=request.user, saved=True)}
        if any(i['id'][2:] not in garments or garments[i['id'][2:]].attributes['category'] != SLOT_CODE[i['slot']]
               for o in result['outfits'] for i in o['items']):
            return _error('GARMENT_CATEGORY_CHANGED', 409)
        for o in result["outfits"]:
            members = [(SLOT_CODE[i["slot"]], garments[i["id"][2:]], i.get("size", "")) for i in o["items"]]
            outfits.append(_create(Outfit.Source.RECOMMENDED, members, user=request.user, score=o["total"], components=o["components"],
                                   reasons=o["reasons"], context=context))
    return _json({"outfits": [serialize(o) for o in outfits], "notes": result["notes"], "band": result["band"],
                  "context": context})


def _local_garments(data):
    rows = data.get('wardrobe')
    if not isinstance(rows, list):
        raise ValueError('Invalid local wardrobe')
    garments = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('Invalid garment')
        garment_id = str(uuid.UUID(row['id']))
        if garment_id in garments:
            raise ValueError('Duplicate garment')
        values = validate_record({key: row[key] for key in ('attributes', 'dimensions', 'notes', 'labels') if key in row})
        garments[garment_id] = Garment(id=garment_id, image='', saved=True, **values)
    return garments


@require_POST
def preview_view(request):
    """Use the same calculation for a device's wardrobe without reading or writing the DB."""
    try:
        data = _read(request, MAX_PREVIEW_BYTES)
        garments = _local_garments(data)
        result, context = _calculate(data, garments)
        outfits = []
        for outfit in result['outfits']:
            items = []
            for item in outfit['items']:
                garment_id = item['id'][2:]
                garment = garments[garment_id]
                items.append({'garment_id': garment_id, 'slot': SLOT_CODE[item['slot']],
                              'name': garment.attributes.get('name', ''), 'colors': garment.attributes.get('colors', []),
                              'image_url': f'local:{garment_id}', 'note': item.get('size', '')})
            outfits.append({'id': str(uuid.uuid5(uuid.NAMESPACE_URL, outfit['key'])), 'key': outfit['key'],
                            'items': items, 'score': outfit['total'], 'components': outfit['components'],
                            'reasons': outfit['reasons'], 'saved': False, 'name': '', 'feedback': None,
                            'created_at': timezone.now().isoformat()})
        return _json({'outfits': outfits, 'notes': result['notes'], 'band': result['band'], 'context': context})
    except TooLarge:
        return _error('BODY_TOO_LARGE', 413)
    except NotJson:
        return _error('JSON_REQUIRED', 415)
    except RecommendError as error:
        return _error(error.code, 422, str(error))
    except (ValueError, TypeError, KeyError, AttributeError, UnicodeDecodeError, OverflowError, RecursionError):
        return _error('INVALID_REQUEST', 400)


@require_POST
def preview_feedback(request):
    try:
        data = _read(request)
        garments = _local_garments(data)
        outfit = data['outfit']
        value = data.get('feedback')
        if not isinstance(outfit, dict) or value not in ('liked', 'disliked', None):
            raise ValueError('Invalid feedback')
        taste = _preference(data.get('preference'))
        if value != outfit.get('feedback') and value is not None:
            items = [to_item({'id': str(g.id), 'attributes': g.attributes, 'labels': g.labels}) for g in garments.values()]
            by_id = {i.id: {'slot': i.slot, 'attrs': i.attrs, 'style': i.style} for i in items if i}
            sign = 1 if value == 'liked' else -1
            taste = preference.apply_feedback(taste, [{'item_id': gid, 'polarity': sign} for gid in by_id], by_id)
        outfit['feedback'] = value
        return _json({'outfit': outfit, 'preference': taste})
    except TooLarge:
        return _error('BODY_TOO_LARGE', 413)
    except NotJson:
        return _error('JSON_REQUIRED', 415)
    except (ValueError, TypeError, KeyError, AttributeError, UnicodeDecodeError, OverflowError, RecursionError):
        return _error('INVALID_REQUEST', 400)


@require_http_methods(["GET", "POST"])
@require_auth
def outfits_view(request):
    if request.method == "GET":
        saved = Outfit.objects.filter(user=request.user, saved=True).prefetch_related("items__garment")
        return account_page(request, saved, serialize, 'outfits', 'updated_at')
    try:
        data = _read(request)
        ids = list(dict.fromkeys(str(uuid.UUID(value)) for value in _strings(data.get("garment_ids"), 3, 40)))
        source = data.get("source", Outfit.Source.USER)
        name = data.get("name", "")
        if source not in (Outfit.Source.USER, Outfit.Source.CHAT) or not isinstance(name, str) or len(name) > 80:
            raise ValueError("Invalid outfit")
        context = data.get("context", {})
        if not isinstance(context, dict) or len(json.dumps(context)) > 2000:
            raise ValueError("Invalid context")
        save_id = context.get("chat_save_id") if source == Outfit.Source.CHAT else None
        if save_id is not None and (not isinstance(save_id, str) or not 1 <= len(save_id) <= 64):
            raise ValueError("Invalid chat save id")
        with transaction.atomic():
            User.objects.select_for_update().get(pk=request.user.pk)
            if save_id is not None:
                existing = Outfit.objects.filter(user=request.user, source=source, context__chat_save_id=save_id).first()
                if existing:
                    if existing.key != '+'.join(sorted(f'w:{gid}' for gid in ids)):
                        return _error('INVALID_OUTFIT', 409)
                    return _json(serialize(existing))
            found = {str(g.id): g for g in Garment.objects.filter(user=request.user, saved=True, pk__in=ids)} if ids else {}
            members = [(g.attributes.get("category"), g, "") for g in (found.get(i) for i in ids) if g]
            slots = [slot for slot, _, _ in members]
            if len(members) != len(ids) or len(set(slots)) != len(slots) or not {"top", "bottom"} <= set(slots) \
                    or not set(slots) <= set(SLOT_ORDER):
                raise ValueError("An outfit needs one saved top and one saved bottom (and optionally one outerwear)")
            outfit = _create(source, members, user=request.user, saved=True, name=name.strip(), context=context)
    except TooLarge:
        return _error("BODY_TOO_LARGE", 413)
    except NotJson:
        return _error("JSON_REQUIRED", 415)
    except (ValueError, TypeError, UnicodeDecodeError, OverflowError) as error:
        return _error("INVALID_OUTFIT", 400, str(error))
    return _json(serialize(outfit), 201)


@require_http_methods(["GET", "PUT"])
@require_auth
def outfit_view(request, outfit_id):
    outfit = get_object_or_404(Outfit, pk=outfit_id, user=request.user)
    if request.method == "PUT":
        try:
            data = _read(request)
            if set(data) - {"saved", "name"} or not isinstance(data.get("saved", outfit.saved), bool):
                raise ValueError("Invalid fields")
            name = data.get("name", outfit.name)
            if not isinstance(name, str) or len(name) > 80:
                raise ValueError("Invalid name")
        except TooLarge:
            return _error("BODY_TOO_LARGE", 413)
        except NotJson:
            return _error("JSON_REQUIRED", 415)
        except (ValueError, TypeError, UnicodeDecodeError) as error:
            return _error("INVALID_OUTFIT", 400, str(error))
        outfit.saved = data.get("saved", outfit.saved)
        outfit.name = name.strip()
        outfit.save(update_fields=["saved", "name", "updated_at"])
    return _json(serialize(outfit))


@require_POST
@require_auth
def feedback_view(request, outfit_id):
    outfit = get_object_or_404(Outfit, pk=outfit_id, user=request.user)
    try:
        data = _read(request)
        value = data.get("feedback")
        if value not in (Outfit.Feedback.LIKED, Outfit.Feedback.DISLIKED, None):
            raise ValueError("Invalid feedback")
        taste = _preference(data.get("preference"))
    except TooLarge:
        return _error("BODY_TOO_LARGE", 413)
    except NotJson:
        return _error("JSON_REQUIRED", 415)
    except (ValueError, TypeError, UnicodeDecodeError, OverflowError) as error:
        return _error("INVALID_FEEDBACK", 400, str(error))
    changed = value != outfit.feedback
    outfit.feedback = value
    outfit.save(update_fields=["feedback", "updated_at"])
    if changed and value is not None:
        # every garment of the outfit moves the taste weights (same rule as a garment like/dislike)
        items = [to_item({"id": str(i.garment_id), "attributes": i.garment.attributes, "labels": i.garment.labels})
                 for i in outfit.items.select_related("garment")]
        garments = {i.id: {"slot": i.slot, "attrs": i.attrs, "style": i.style} for i in items if i}
        sign = 1 if value == Outfit.Feedback.LIKED else -1
        taste = preference.apply_feedback(taste, [{"item_id": gid, "polarity": sign} for gid in garments], garments)
    return _json({"outfit": serialize(outfit), "preference": taste})


@require_GET
def weather_view(request):
    try:
        lat = _number(float(request.GET.get("lat", weather.SEOUL[0])), -90, 90)
        lon = _number(float(request.GET.get("lon", weather.SEOUL[1])), -180, 180)
    except ValueError:
        return _error("INVALID_LOCATION", 400)
    try:
        current = weather.current(lat, lon)
    except weather.WeatherError:
        return _error("WEATHER_UNAVAILABLE", 502)
    response = JsonResponse({**current.public(), "band": season.band(current.apparent).label},
                            json_dumps_params={"ensure_ascii": False})
    response["Cache-Control"] = "max-age=600"
    return response
