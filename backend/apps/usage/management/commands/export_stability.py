import csv
import json
from collections import defaultdict
from zoneinfo import ZoneInfo

from django.core.management.base import BaseCommand, CommandError
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from apps.usage.models import StabilityEvent


class Command(BaseCommand):
    help = 'Export sanitized app diagnostics, or observed daily installation-based rates (KST).'

    def add_arguments(self, parser):
        parser.add_argument('--since', help='Inclusive ISO-8601 timestamp with timezone')
        parser.add_argument('--until', help='Exclusive ISO-8601 timestamp with timezone')
        parser.add_argument('--summary', action='store_true', help='Daily observed rates; not Android vitals')

    def handle(self, *args, **options):
        events = StabilityEvent.objects.order_by('occurred_at', 'id')
        bounds = {}
        for argument, lookup in [('since', 'occurred_at__gte'), ('until', 'occurred_at__lt')]:
            if options[argument]:
                value = parse_datetime(options[argument])
                if value is None or timezone.is_naive(value):
                    raise CommandError(f'--{argument} must include a timezone')
                bounds[argument] = value
                events = events.filter(**{lookup: value})
        if len(bounds) == 2 and bounds['since'] >= bounds['until']:
            raise CommandError('--since must be before --until')
        writer = csv.writer(self.stdout)
        if not options['summary']:
            fields = ['id', 'installation_id', 'session_id', 'kind', 'source', 'occurred_at', 'received_at',
                      'app_version', 'sdk_int', 'foreground', 'exception_type', 'frames', 'duration_ms', 'exit_reason', 'exit_status']
            writer.writerow(fields)
            for event in events.iterator():
                writer.writerow([json.dumps(event.frames) if key == 'frames' else getattr(event, key) for key in fields])
            return
        days = defaultdict(lambda: defaultdict(set))
        for event in events.iterator():
            day = days[event.occurred_at.astimezone(ZoneInfo('Asia/Seoul')).date()]
            device = event.installation_id
            if event.kind == 'active':
                day['active'].add(device)
                if event.sdk_int >= 30:
                    day['eligible'].add(device)
            elif event.foreground:
                day[event.kind].add(device)
        writer.writerow(['date_kst', 'active_installations', 'anr_eligible_installations', 'anr_affected_installations',
                         'observed_anr_rate_pct', 'stall_affected_installations', 'observed_stall_rate_pct',
                         'crash_affected_installations', 'observed_crash_rate_pct', 'anr_without_active_day'])
        def rate(affected, active):
            return round(100 * len(affected & active) / len(active), 4) if active else ''
        for date, day in sorted(days.items()):
            writer.writerow([date, len(day['active']), len(day['eligible']), len(day['anr'] & day['eligible']),
                             rate(day['anr'], day['eligible']), len(day['stall'] & day['active']),
                             rate(day['stall'], day['active']), len(day['crash'] & day['active']),
                             rate(day['crash'], day['active']), len(day['anr'] - day['eligible'])])
