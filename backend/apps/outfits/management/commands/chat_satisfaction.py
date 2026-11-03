"""Scoped HE satisfaction: latest in-chat rating per participant/outfit, at least 20 ratings."""

from django.core.management.base import BaseCommand, CommandError
from django.db.models import Count, Q
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from apps.outfits.models import FeedbackRecord, Outfit
from apps.users.models import User

PASS_THRESHOLD = 0.5
MIN_RESPONSES = 20


def satisfaction(*, since, participants, until=None) -> dict:
    """Participants are an explicit allowlist of HE usernames, excluding team/test accounts."""
    if not participants or timezone.is_naive(since) or (until is not None and (timezone.is_naive(until) or until <= since)):
        raise ValueError("Specify participants and a timezone-aware evaluation period")
    rated = FeedbackRecord.objects.filter(channel="chat", user__username__in=participants,
                                           feedback__isnull=False, rated_at__gte=since)
    if until is not None:
        rated = rated.filter(rated_at__lt=until)
    counts = rated.aggregate(liked=Count("id", filter=Q(feedback=Outfit.Feedback.LIKED)),
                             disliked=Count("id", filter=Q(feedback=Outfit.Feedback.DISLIKED)))
    liked, disliked = counts["liked"], counts["disliked"]
    total = liked + disliked
    rate = liked / total if total else None
    complete = total >= MIN_RESPONSES
    passed = complete and rate >= PASS_THRESHOLD
    return {"liked": liked, "disliked": disliked, "total": total, "rate": rate,
            "threshold": PASS_THRESHOLD, "minimum": MIN_RESPONSES, "passed": passed,
            "verdict": "INCOMPLETE" if not complete else "PASS" if passed else "FAIL"}


class Command(BaseCommand):
    help = "명시한 HE 기간·참여 계정의 채팅 만족도를 집계합니다 (최소 20건, 50% 이상)."

    def add_arguments(self, parser):
        parser.add_argument("--since", required=True, help="평가 시작 시각 (예: 2026-11-03T00:00:00+09:00)")
        parser.add_argument("--until", help="평가 종료 시각 (미포함), 생략하면 실행 시각")
        parser.add_argument("--participants", required=True, nargs="+", help="팀·테스트 계정을 제외한 HE 참여자 아이디")

    def handle(self, *args, **options):
        try:
            since = parse_datetime(options["since"])
            until = parse_datetime(options["until"]) if options["until"] else timezone.now()
        except ValueError:
            raise CommandError("유효한 ISO 8601 시각을 지정하세요.")
        if since is None or until is None or timezone.is_naive(since) or timezone.is_naive(until) or until <= since:
            raise CommandError("시작·종료 시각은 시간대를 포함하고 종료는 시작 이후여야 합니다.")
        participants = sorted(set(options["participants"]))
        unknown = set(participants) - set(User.objects.filter(username__in=participants).values_list("username", flat=True))
        if unknown:
            raise CommandError("존재하지 않는 참여 계정: " + ", ".join(sorted(unknown)))
        result = satisfaction(since=since, until=until, participants=participants)
        self.stdout.write(f"평가 기간 [{since.isoformat()}, {until.isoformat()}) / 참여 계정 {len(participants)}개\n"
                          f"좋아요 {result['liked']} / 별로예요 {result['disliked']} (응답 {result['total']}건, 최소 {MIN_RESPONSES}건)")
        rate = f"{result['rate']:.1%}" if result["rate"] is not None else "집계 없음"
        self.stdout.write(f"만족도 {rate} (기준 {PASS_THRESHOLD:.0%} 이상) → {result['verdict']}")
