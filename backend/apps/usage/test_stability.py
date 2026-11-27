import csv
import io
import json
import uuid

from django.core.management import call_command
from django.core.management.base import CommandError
from django.test import TestCase
from django.utils import timezone

from apps.users.auth import issue_token
from apps.users.models import User

from .models import StabilityEvent


class StabilityTests(TestCase):
    def setUp(self):
        user = User.objects.create_user(username='health_tester', password='password123')
        _, token = issue_token(user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        self.installation = str(uuid.uuid4())

    def event(self, **changes):
        return {'id': str(uuid.uuid4()), 'installation_id': self.installation, 'session_id': str(uuid.uuid4()),
                'kind': 'crash', 'source': 'java', 'occurred_at': timezone.now().isoformat(),
                'app_version': '0.2.2', 'sdk_int': 35, 'foreground': True,
                'exception_type': 'java.lang.IllegalStateException',
                'frames': ['com.swpp.stylemate.MainActivity.onCreate(MainActivity.kt:42)'],
                'duration_ms': None, 'exit_reason': None, 'exit_status': None, **changes}

    def post(self, events):
        return self.client.post('/api/usage/stability/', json.dumps({'events': events}), content_type='application/json')

    def test_authenticated_idempotent_immutable_collection_and_no_read_endpoint(self):
        event = self.event()
        for _ in range(2):
            response = self.post([event])
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['accepted'], [event['id']])
        self.assertEqual(response['Cache-Control'], 'no-store')
        self.assertEqual(StabilityEvent.objects.count(), 1)
        self.post([{**event, 'app_version': '9.9'}])
        self.assertEqual(StabilityEvent.objects.get().app_version, '0.2.2')
        self.assertEqual(self.client.get('/api/usage/stability/').status_code, 405)
        self.client.defaults.clear()
        self.assertEqual(self.post([self.event()]).status_code, 401)

    def test_accepts_active_anr_stall_and_native_crash(self):
        rows = [self.event(kind='active', source='lifecycle', frames=[], exception_type=''),
                self.event(kind='anr', source='os_exit', exception_type='', exit_reason=6, exit_status=0),
                self.event(kind='stall', source='watchdog', exception_type='', duration_ms=6000, sdk_int=26),
                self.event(source='os_exit', exception_type='', frames=[], exit_reason=5, exit_status=11)]
        self.assertEqual(self.post(rows).status_code, 200)
        self.assertEqual(StabilityEvent.objects.count(), 4)

    def test_invalid_or_sensitive_payload_is_rejected_atomically(self):
        invalid = [{'message': 'private'}, {'installation_id': 'android-hardware-id'}, {'session_id': '../x'},
                   {'frames': ['token=private']}, {'frames': ['com.X.m(https://private/path)']},
                   {'frames': ['com.X.m(X.kt:1)'] * 21}, {'exception_type': 'error: private text'},
                   {'exception_type': ''}, {'sdk_int': True}, {'sdk_int': 25}, {'foreground': 1},
                   {'app_version': '=FORMULA()'}, {'occurred_at': '2026-11-30T00:00:00'},
                   {'duration_ms': 5000}, {'exit_reason': 4}, {'kind': 'anr', 'source': 'watchdog'},
                   {'kind': 'stall', 'source': 'watchdog', 'exception_type': '', 'duration_ms': 4999},
                   {'kind': 'stall', 'source': 'watchdog', 'exception_type': '', 'duration_ms': True},
                   {'kind': 'stall', 'source': 'watchdog', 'exception_type': '', 'duration_ms': 5000, 'foreground': False},
                   {'kind': 'anr', 'source': 'os_exit', 'exception_type': '', 'sdk_int': 29, 'exit_reason': 6, 'exit_status': 0},
                   {'kind': 'anr', 'source': 'os_exit', 'exception_type': '', 'exit_reason': 4, 'exit_status': 0},
                   {'kind': 'active', 'source': 'lifecycle', 'exception_type': '', 'frames': [], 'foreground': False}]
        for changes in invalid:
            with self.subTest(changes=changes):
                self.assertEqual(self.post([self.event(), self.event(**changes)]).status_code, 400)
        self.assertFalse(StabilityEvent.objects.exists())

    def test_batch_content_type_and_byte_limits(self):
        self.assertEqual(self.post([]).status_code, 400)
        self.assertEqual(self.post([self.event() for _ in range(6)]).status_code, 400)
        self.assertEqual(self.client.post('/api/usage/stability/', '{}', content_type='text/plain').status_code, 415)
        self.assertEqual(self.client.post('/api/usage/stability/', 'x' * (64 * 1024 + 1),
                                          content_type='application/json').status_code, 413)

    def test_export_and_observed_daily_rates_separate_unsupported_android_and_stalls(self):
        stamp = '2026-11-26T15:30:00Z'  # November 27 in KST.
        first, second, unsupported = self.installation, str(uuid.uuid4()), str(uuid.uuid4())
        def record(device, kind, sdk=35, **changes):
            defaults = dict(installation_id=device, kind=kind, sdk_int=sdk, occurred_at=stamp, exception_type='')
            if kind == 'active':
                defaults.update(source='lifecycle', frames=[])
            elif kind == 'anr':
                defaults.update(source='os_exit', exit_reason=6, exit_status=0)
            elif kind == 'stall':
                defaults.update(source='watchdog', duration_ms=5000)
            else:
                defaults.update(exception_type='java.lang.Error')
            defaults.update(changes)
            self.assertEqual(self.post([self.event(**defaults)]).status_code, 200)
        for device in (first, second):
            record(device, 'active')
        record(first, 'active')  # Repeated activity does not inflate the denominator.
        record(unsupported, 'active', sdk=29)
        record(first, 'anr')
        record(first, 'anr')
        record(unsupported, 'stall', sdk=29)
        record(first, 'crash')
        record(second, 'crash', foreground=False)
        record(str(uuid.uuid4()), 'anr')  # Missing activity is visible, never divided into an unrelated denominator.
        output = io.StringIO()
        call_command('export_stability', summary=True, stdout=output)
        row = list(csv.DictReader(io.StringIO(output.getvalue())))[0]
        self.assertEqual(row['date_kst'], '2026-11-27')
        self.assertEqual(row['active_installations'], '3')
        self.assertEqual(row['anr_eligible_installations'], '2')
        self.assertEqual(row['anr_affected_installations'], '1')
        self.assertEqual(float(row['observed_anr_rate_pct']), 50)
        self.assertEqual(float(row['observed_stall_rate_pct']), 33.3333)
        self.assertEqual(row['crash_affected_installations'], '1')
        self.assertEqual(row['anr_without_active_day'], '1')
        raw = io.StringIO()
        call_command('export_stability', since='2026-11-26T15:00:00Z', until='2026-11-27T15:00:00Z', stdout=raw)
        self.assertEqual(len(list(csv.DictReader(io.StringIO(raw.getvalue())))), 10)
        self.assertNotIn('health_tester', raw.getvalue())
        empty = io.StringIO()
        call_command('export_stability', until='2000-01-01T00:00:00Z', stdout=empty)
        self.assertEqual(len(list(csv.DictReader(io.StringIO(empty.getvalue())))), 0)

    def test_no_supported_activity_has_blank_anr_rate_not_a_claim_of_zero(self):
        self.post([self.event(kind='active', source='lifecycle', frames=[], exception_type='', sdk_int=29)])
        output = io.StringIO()
        call_command('export_stability', summary=True, stdout=output)
        self.assertEqual(list(csv.DictReader(io.StringIO(output.getvalue())))[0]['observed_anr_rate_pct'], '')

    def test_export_rejects_invalid_boundaries(self):
        for options in ({'since': '2026-11-27'}, {'until': 'nonsense'},
                        {'since': '2026-11-28T00:00:00Z', 'until': '2026-11-27T00:00:00Z'}):
            with self.subTest(options=options), self.assertRaises(CommandError):
                call_command('export_stability', stdout=io.StringIO(), **options)
