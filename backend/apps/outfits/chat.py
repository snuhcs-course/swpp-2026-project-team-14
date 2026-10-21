"""Stateless chat previews. Only the existing POST /outfits/ saves a result."""
from __future__ import annotations

import uuid
from threading import BoundedSemaphore

from django.utils import timezone
from django.views.decorators.http import require_POST

from apps.body_profiles.profile import body_from_request
from apps.users.auth import require_auth
from apps.wardrobe.models import Garment
from recommendation import chat_intent, season
from recommendation.search import RecommendError, Request, recommend
from recommendation.stylist import _want_score
from recommendation.vocab import WARDROBE_CATEGORY

from . import views

SLOTS = ('top', 'bottom', 'outerwear')
DIRECTIONS = ('brighter', 'darker', 'warmer', 'cooler', 'formal', 'casual', 'looser', 'fitted', 'colorful', 'simpler', 'different')
OPPOSITES = [('brighter', 'darker'), ('warmer', 'cooler'), ('formal', 'casual'), ('looser', 'fitted'), ('colorful', 'simpler')]
SLOTS_GATE = BoundedSemaphore(2)


def read(data, request):
    body = body_from_request(request, data)
    current = views._strings(data.get('garment_ids'), 3, 40)
    locked = views._strings(data.get('locked_ids'), 3, 40)
    if not current or not set(locked) <= set(current):
        raise ValueError('Invalid current outfit or locks')
    directions = views._strings(data.get('directions'), 6, 20, DIRECTIONS)
    text = data.get('text', '')
    if not isinstance(text, str) or len(text) > 400 or not (text.strip() or directions):
        raise ValueError('A message or direction is required')
    history = data.get('history', [])
    if not isinstance(history, list) or len(history) > 6 or any(
        not isinstance(h, dict) or set(h) != {'role', 'text'} or h['role'] not in ('user', 'assistant')
        or not isinstance(h['text'], str) or len(h['text']) > 500 for h in history):
        raise ValueError('Invalid history')
    pref_fit = data.get('pref_fit', data['body'].get('preferred_fit', 'regular'))
    if pref_fit not in views.PREF_FITS:
        raise ValueError('Invalid fit')
    styles = views._strings(data.get('styles', data['body'].get('preferred_styles')), 6, 20, views.PROFILE_STYLES)
    temp, wet, context = views._weather(data)
    taste = views._preference(data.get('preference'))
    seen = views._strings(data.get('seen_keys'), views.MAX_SEEN, 400)
    return body, current, locked, directions, text.strip(), history, pref_fit, styles, temp, wet, context, taste, seen


def preview(garments, items, context, reasons=None, key=None, local=False):
    key = key or '+'.join(sorted(i.id for i in items))
    return {'id': str(uuid.uuid5(uuid.NAMESPACE_URL, 'stylemate/chat/' + key)), 'key': key,
            'source': 'chat', 'saved': False, 'name': '', 'score': None, 'components': {},
            'reasons': reasons or [], 'context': context, 'feedback': None, 'created_at': timezone.now().isoformat(),
            'items': [{'garment_id': i.id[2:], 'slot': views.SLOT_CODE[i.slot],
                       'name': garments[i.id[2:]].attributes.get('name', ''),
                       'image_url': f'local:{i.id[2:]}' if local else f'/api/wardrobe/items/{i.id[2:]}/image/',
                       'note': i.fit_note or ''} for i in sorted(items, key=lambda i: views.SLOT_ORDER.index(views.SLOT_CODE[i.slot]))]}


@require_POST
@require_auth
def chat_view(request):
    return _handle(request, local=False)


@require_POST
def preview_chat_view(request):
    return _handle(request, local=True)


def _handle(request, local):
    try:
        data = views._read(request, views.MAX_PREVIEW_BYTES if local else views.MAX_REQUEST_BYTES)
        args = read(data, request)
        garments = views._local_garments(data) if local else {
            str(g.id): g for g in Garment.objects.filter(user=request.user, saved=True)}
    except views.TooLarge:
        return views._error('BODY_TOO_LARGE', 413)
    except views.NotJson:
        return views._error('JSON_REQUIRED', 415)
    except (ValueError, KeyError, TypeError, UnicodeDecodeError, OverflowError):
        return views._error('INVALID_CHAT_REQUEST', 400, '코디와 수정 요청을 확인해 주세요.')
    if not SLOTS_GATE.acquire(blocking=False):
        return views._error('CHAT_BUSY', 429, '다른 코디를 수정 중이에요. 잠시 후 다시 시도해 주세요.')
    try:
        return process(garments, local, *args)
    except chat_intent.ChatModelError:
        return views._error('CHAT_MODEL_UNAVAILABLE', 503, '요청을 해석하지 못했어요. 잠시 후 다시 시도해 주세요.')
    finally:
        SLOTS_GATE.release()


def process(garments, local, body, current_ids, locked, directions, text, history, pref_fit, styles, temp, wet, context, taste, seen):
    if any(gid not in garments for gid in current_ids):
        return views._error('CURRENT_GARMENT_MISSING', 422, '수정 중인 옷이 삭제됐어요. 홈에서 코디를 다시 골라 주세요.')
    current = {garments[gid].attributes.get('category'): gid for gid in current_ids}
    if len(current) != len(current_ids) or not {'top', 'bottom'} <= set(current) or not set(current) <= set(SLOTS):
        return views._error('INVALID_OUTFIT', 400)
    items = [i for i in (views._garment_item(g, body) for g in garments.values()) if i]
    by_id = {i.id[2:]: i for i in items}
    before = {slot: by_id[gid] for slot, gid in current.items()}
    extraction, parsed_by, notice = (chat_intent.parse(text, {
        'current': [{'slot': slot, 'name': i.name} for slot, i in before.items()],
        'locked_slots': [slot for slot, gid in current.items() if gid in locked], 'history': history,
        'wardrobe': [{'id': i.id[2:], 'slot': views.SLOT_CODE[i.slot], 'name': i.name,
                      'category': i.attrs.get('카테고리'), 'color': i.attrs.get('색상')}
                     for i in items[:100]],
    }) if text else (chat_intent.empty(), 'buttons', None))
    notes = [notice] if notice else []
    def response(status, message, **extra):
        return views._json({'status': status, 'message': message, 'outfit': None, 'changed_ids': [],
                            'locked_ids': locked, 'unlock_ids': [], 'notes': notes, 'parsed_by': parsed_by, **extra})
    if extraction['action'] == 'undo':
        return response('undo', '이전 코디로 되돌릴게요.')
    if extraction['action'] == 'clarify':
        return response('clarify', extraction['question'] or '어떤 부분을 바꾸고 싶으세요?')
    extraction['wants'] += [{'slot': None, 'want': d} for d in directions if d != 'different']
    wants_set = {w['want'] for w in extraction['wants']}
    if any(a in wants_set and b in wants_set for a, b in OPPOSITES):
        return response('clarify', '서로 반대인 수정 조건이 있어요. 원하는 방향을 하나만 골라 주세요.')
    target_ids = extraction['target_garment_ids']
    if any(gid not in by_id for gid in target_ids):
        return response('clarify', '말씀하신 옷을 찾지 못했어요. 옷장에 등록한 이름으로 알려 주세요.')
    targets = {views.SLOT_CODE[by_id[gid].slot]: gid for gid in target_ids}
    if len(targets) != len(target_ids):
        return response('clarify', '같은 종류의 옷은 한 벌만 골라 주세요.')
    requested = set(extraction['change_slots']) | set(targets)
    conflict = [gid for slot, gid in current.items() if gid in locked and slot in requested]
    if conflict:
        names = ' · '.join(garments[gid].attributes['name'] for gid in conflict)
        return response('unlock', f'{names}의 잠금을 풀고 바꿀까요?', unlock_ids=conflict)
    kept = {slot for slot in extraction['keep_slots'] if slot in current}
    if requested & kept:
        return response('clarify', '유지할 옷과 바꿀 옷을 다시 골라 주세요.')
    # An explicit single-slot edit also fixes every unrequested slot for this turn.
    fixed_slots = {slot for slot, gid in current.items() if gid in locked} | kept
    if requested:
        fixed_slots |= set(current) - requested
    fixed = {WARDROBE_CATEGORY[slot]: 'w:' + current[slot] for slot in fixed_slots}
    fixed.update({WARDROBE_CATEGORY[slot]: 'w:' + gid for slot, gid in targets.items()})
    if set(current) <= fixed_slots:
        return response('clarify', '바꿀 옷의 잠금을 하나 이상 풀어 주세요.')
    if extraction['remove_outerwear'] and 'outerwear' in fixed_slots:
        return response('unlock', '아우터 잠금을 풀고 뺄까요?', unlock_ids=[current['outerwear']])
    # Textual keep constraints apply to this edit only. Persistent locks are user-owned.
    new_locked = list(locked)
    allowed = []
    for item in items:
        slot = views.SLOT_CODE[item.slot]
        if slot in fixed_slots:
            if item.id[2:] == current[slot]:
                allowed.append(item)
            continue
        if requested and slot not in requested and slot not in current:
            continue
        if extraction['remove_outerwear'] and slot == 'outerwear':
            continue
        if slot in targets and item.id[2:] != targets[slot]:
            continue
        color = item.attrs.get('색상')
        if color in extraction['avoid_colors'] or (extraction['target_colors'] and color not in extraction['target_colors']):
            continue
        allowed.append(item)
    # Colour/fit/style requests should not silently return an opposite direction.
    by_slot = {s: [i for i in allowed if i.slot == s] for s in WARDROBE_CATEGORY.values()}
    for slot in by_slot:
        if slot in fixed:
            continue
        wishes = [w['want'] for w in extraction['wants'] if w['slot'] in (None, views.SLOT_CODE[slot])]
        for want in wishes:
            if want in ('warmer', 'cooler'):
                continue
            positive = [i for i in by_slot[slot] if _want_score(want, i, before.get(views.SLOT_CODE[slot])) > 0]
            by_slot[slot] = positive
    allowed = [i for pool in by_slot.values() for i in pool]
    shift = 5 if 'warmer' in wants_set else -5 if 'cooler' in wants_set else 0
    try:
        result = recommend(views.models(), Request(body=body, pref_fit=pref_fit, styles=styles,
            temp=max(-40, min(50, temp - shift)), wet=wet, mode='wardrobe', wardrobe=allowed,
            preference=taste, seen_outfits=list(dict.fromkeys([*seen, '+'.join(sorted('w:' + gid for gid in current_ids))])),
            count=5, fixed=fixed, directions={s: [w['want'] for w in extraction['wants']
                    if w['slot'] in (None, views.SLOT_CODE[s])] for s in by_slot}))
    except RecommendError:
        return response('unchanged', '지금 옷장에는 조건에 맞는 다른 조합이 없어요. 조건이나 잠금을 조금 바꿔 보세요.', locked_ids=new_locked)
    choices = [o for o in result['outfits'] if {'상의', '하의'} <= {i['slot'] for i in o['items']}
               and all(any(i['id'] == gid for i in o['items']) for gid in fixed.values())]
    if not choices:
        return response('unchanged', '유지할 옷을 포함한 다른 코디를 찾지 못했어요. 잠금이나 조건을 바꿔 보세요.', locked_ids=new_locked)
    changed = next((o for o in choices if {i['id'][2:] for i in o['items']} != set(current_ids)), None)
    if changed is None:
        return response('unchanged', '현재 코디가 조건에 가장 가까워요. 다른 조건으로 바꿔 볼까요?', locked_ids=new_locked)
    selected = [by_id[i['id'][2:]] for i in changed['items']]
    changed_ids = [i.id[2:] for i in selected if i.id[2:] not in current_ids]
    removed = [slot for slot, gid in current.items() if gid not in {i.id[2:] for i in selected} and slot == 'outerwear' and extraction['remove_outerwear']]
    descriptions = [f'{views.SLOT_CODE[i.slot]}' for i in selected if i.id[2:] in changed_ids]
    labels = {'top': '상의', 'bottom': '하의', 'outerwear': '아우터'}
    message = ' · '.join(labels[s] for s in descriptions) + '를 바꿨어요.' if descriptions else '아우터를 뺐어요.'
    if removed and descriptions:
        message += ' 아우터는 뺐어요.'
    if new_locked:
        message += ' 잠근 옷은 그대로 유지했어요.'
    context = {**context, 'pref_fit': pref_fit, 'styles': styles}
    out = preview(garments, selected, context, changed['reasons'], changed['key'], local=local)
    return response('updated', message, outfit=out, changed_ids=changed_ids, locked_ids=new_locked,
                    notes=notes + result['notes'])
