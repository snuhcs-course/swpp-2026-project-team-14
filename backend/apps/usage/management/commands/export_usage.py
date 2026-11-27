import csv

from django.core.management.base import BaseCommand, CommandError
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from apps.usage.models import UsageEvent


class Command(BaseCommand):
    help = 'Export alpha-test usage events as CSV to stdout (no usernames or message contents).'

    def add_arguments(self, parser):
        parser.add_argument('--since', help='Inclusive ISO-8601 timestamp with timezone')
        parser.add_argument('--until', help='Exclusive ISO-8601 timestamp with timezone')

    def handle(self, *args, **options):
        events = UsageEvent.objects.order_by('occurred_at', 'id')
        for argument, lookup in [('since', 'occurred_at__gte'), ('until', 'occurred_at__lt')]:
            if options[argument]:
                value = parse_datetime(options[argument])
                if value is None or timezone.is_naive(value):
                    raise CommandError(f'--{argument} must include a timezone')
                events = events.filter(**{lookup: value})
        writer = csv.writer(self.stdout)
        writer.writerow(['event_id', 'user_id', 'attempt_id', 'session_id', 'name', 'phase', 'outcome',
                         'occurred_at', 'received_at', 'duration_ms', 'error_code', 'source', 'status',
                         'total_count', 'duplicate_count', 'already_saved', 'clock_reset', 'app_version'])
        for event in events.iterator():
            writer.writerow([event.id, event.user_id, event.attempt_id, event.session_id, event.name, event.phase,
                             event.outcome, event.occurred_at.isoformat(), event.received_at.isoformat(),
                             event.duration_ms, event.error_code,
                             *[event.details.get(key, '') for key in ('source', 'status', 'total_count', 'duplicate_count',
                                                                    'already_saved', 'clock_reset', 'app_version')]])
