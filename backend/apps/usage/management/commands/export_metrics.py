# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, review pending (Kihwan Kim)
import csv
from zoneinfo import ZoneInfo

from django.core.management.base import BaseCommand, CommandError
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from apps.usage.models import Metric

KST = ZoneInfo('Asia/Seoul')


class Command(BaseCommand):
    help = '기능별 만족도 별점(metrics)을 CSV로 stdout에 출력합니다. 로그인 아이디는 포함하지 않습니다.'

    def add_arguments(self, parser):
        parser.add_argument('--since', help='포함 시작 시각 (시간대 포함 ISO 8601, 예: 2026-12-07T00:00:00+09:00)')
        parser.add_argument('--until', help='미포함 종료 시각 (시간대 포함 ISO 8601)')

    def handle(self, *args, **options):
        rows = Metric.objects.order_by('rated_at', 'id')
        for argument, lookup in [('since', 'rated_at__gte'), ('until', 'rated_at__lt')]:
            if options[argument]:
                value = parse_datetime(options[argument])
                if value is None or timezone.is_naive(value):
                    raise CommandError(f'--{argument}에는 시간대를 포함한 ISO 8601 시각을 지정하세요.')
                rows = rows.filter(**{lookup: value})
        writer = csv.writer(self.stdout)
        writer.writerow(['user_id', 'task', 'wait_score', 'quality_score', 'convenience_score', 'rated_at'])
        for row in rows.iterator():
            writer.writerow([row.user_id, row.task, row.wait_score, row.quality_score, row.convenience_score,
                             timezone.localtime(row.rated_at, KST).isoformat()])
