# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, review pending (Kihwan Kim)
"""기능별 만족도 별점: 팝업을 띄울지 판단하고, 제출된 평가를 저장한다."""
import logging
from datetime import timedelta

from django.db import transaction
from django.utils import timezone

from .models import Metric, NoSeen, RatingTask

logger = logging.getLogger(__name__)

# 같은 작업을 평가한 뒤 다시 묻기까지의 간격
COOLDOWN = timedelta(hours=12)
TASKS = frozenset(RatingTask.values)
DETAILS = frozenset({'task', 'submitted', 'wait', 'quality', 'convenience', 'no_seen'})


def should_prompt(user, task):
    """기존 API 응답의 rating_prompt 값. 조회만 하며, 실패해도 원래 응답을 깨뜨리지 않는다."""
    try:
        if NoSeen.objects.filter(user=user, task=task).exists():
            return False
        return not Metric.objects.filter(user=user, task=task, rated_at__gte=timezone.now() - COOLDOWN).exists()
    except Exception:
        logger.exception('Could not decide the rating prompt')
        return False


def _score(value):
    # 0~5 범위의 0.5 단위만 허용한다. bool은 int의 하위 타입이라 따로 막는다.
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 5 or (value * 2) % 1:
        raise ValueError('Invalid score')
    return float(value)


def validate_rating(data, stamp):
    """usage 수집 API의 task_rating 이벤트를 검사한다. 다른 이벤트와 같은 바깥 필드 구성을 따른다."""
    if (data['phase'] != 'finished' or data['outcome'] != 'success' or data['duration_ms'] is not None
            or data['error_code'] != ''):
        raise ValueError('Invalid rating event')
    details = data['details']
    if not isinstance(details, dict) or set(details) != DETAILS or details['task'] not in TASKS:
        raise ValueError('Invalid rating details')
    if type(details['submitted']) is not bool or type(details['no_seen']) is not bool:
        raise ValueError('Invalid rating flags')
    scores = {key: _score(details[key]) for key in ('wait', 'quality', 'convenience')}
    return {**data, 'occurred_at': stamp, 'details': {**details, **scores}}


def save_rating(user, event):
    """제출이면 metrics에 새 행을, 체크박스를 켰으면 no_seen에 행을 넣는다. 건너뛰기만 하면 기록하지 않는다.

    평가 시각은 클라이언트가 평가한 시각(occurred_at)이다. 재전송은 변경되지 않는
    이벤트 ID로 식별하고, DB의 고유 제약으로 동시에 도착한 요청도 중복 저장하지 않는다.
    """
    details = event['details']
    task = details['task']
    with transaction.atomic():
        if details['submitted']:
            Metric.objects.get_or_create(user=user, event_id=event['id'], defaults={
                'task': task, 'wait_score': details['wait'], 'quality_score': details['quality'],
                'convenience_score': details['convenience'], 'rated_at': event['occurred_at'],
            })
        if details['no_seen']:
            NoSeen.objects.get_or_create(user=user, task=task)
