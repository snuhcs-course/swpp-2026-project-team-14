import uuid

from django.conf import settings
from django.db import models


class UsageEvent(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    attempt_id = models.UUIDField()
    session_id = models.UUIDField()
    name = models.CharField(max_length=32)
    phase = models.CharField(max_length=12)
    outcome = models.CharField(max_length=12, blank=True)
    occurred_at = models.DateTimeField()
    received_at = models.DateTimeField(auto_now_add=True)
    duration_ms = models.PositiveBigIntegerField(null=True)
    error_code = models.CharField(max_length=64, blank=True)
    details = models.JSONField(default=dict)

    class Meta:
        constraints = [models.UniqueConstraint(fields=['user', 'attempt_id', 'phase'], name='usage_attempt_phase')]
        indexes = [models.Index(fields=['name', 'occurred_at'], name='usage_name_time')]


# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, review pending (Kihwan Kim)
class RatingTask(models.TextChoices):
    """별점 팝업을 띄우는 세 작업. DB에는 영문 코드로 저장하고 화면에서만 한글로 보여 준다."""
    RECOMMENDATION = 'recommendation', '코디 추천'
    GARMENT_REGISTRATION = 'garment_registration', '옷 등록'
    VIRTUAL_FITTING = 'virtual_fitting', '가상 피팅'


class Metric(models.Model):
    """제출한 만족도 평가 한 건. 평가할 때마다 새 행을 추가하고 수정하지 않는다."""
    # 탈퇴 등으로 user가 삭제돼도 평가 기록과 user_id 값은 남도록 DB 수준 FK 제약을 두지 않는다.
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.DO_NOTHING, db_constraint=False,
                             related_name='+')
    task = models.CharField(max_length=32, choices=RatingTask.choices)
    # Older ratings have no event ID; keep them intact when adding retry protection.
    event_id = models.UUIDField(null=True, editable=False)
    wait_score = models.FloatField()
    quality_score = models.FloatField()
    convenience_score = models.FloatField()
    rated_at = models.DateTimeField()

    class Meta:
        db_table = 'metrics'
        # 판단 로직의 "최근 12시간 안에 이 작업을 평가했는가" 조회가 이 인덱스로 끝난다.
        indexes = [models.Index(fields=['user', 'task', 'rated_at'], name='metrics_user_task_time')]
        constraints = [
            models.UniqueConstraint(fields=['user', 'event_id'], name='metrics_user_event'),
            models.CheckConstraint(condition=models.Q(wait_score__gte=0, wait_score__lte=5,
                                                      quality_score__gte=0, quality_score__lte=5,
                                                      convenience_score__gte=0, convenience_score__lte=5),
                                   name='metrics_score_range'),
        ]


class NoSeen(models.Model):
    """사용자가 "같은 평가 다시 보지 않기"를 고른 작업. (user, task) 하나로 의미가 끝나므로 복합 PK로 둔다."""
    pk = models.CompositePrimaryKey('user', 'task')
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='+')
    task = models.CharField(max_length=32, choices=RatingTask.choices)

    class Meta:
        db_table = 'no_seen'
