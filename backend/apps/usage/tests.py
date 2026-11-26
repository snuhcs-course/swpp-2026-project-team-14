import csv
import io
import json
import uuid
from unittest.mock import patch

from django.core.management import call_command
from django.test import TestCase
from django.http import JsonResponse
from django.utils import timezone

from apps.outfits.models import Outfit
from apps.users.auth import issue_token
from apps.users.models import User
from apps.wardrobe.models import Garment

from .models import UsageEvent
from .recording import record_outfit_event


class UsageFixture(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(username='alpha_tester', password='password123')
        _, token = issue_token(self.user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'

    def event(self, **changes):
        return {'id': str(uuid.uuid4()), 'attempt_id': str(uuid.uuid4()), 'session_id': str(uuid.uuid4()),
                'name': 'garment_registration', 'phase': 'finished', 'outcome': 'success',
                'occurred_at': timezone.now().isoformat(), 'duration_ms': 1234, 'error_code': '',
                'details': {'source': 'wardrobe', 'app_version': '0.2.2'}, **changes}

    def post(self, events):
        return self.client.post('/api/usage/events/', json.dumps({'events': events}), content_type='application/json')


class UsageTests(UsageFixture):
    def test_authenticated_collection_and_lost_response_retry(self):
        event = self.event()
        for _ in range(2):
            response = self.post([event])
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['accepted'], [event['id']])
        self.assertEqual(UsageEvent.objects.count(), 1)
        saved = UsageEvent.objects.get()
        self.assertEqual((saved.user, saved.duration_ms), (self.user, 1234))
        self.assertEqual(response['Cache-Control'], 'no-store')

    def test_auth_required_and_no_public_read_endpoint(self):
        self.client.defaults.clear()
        self.assertEqual(self.post([self.event()]).status_code, 401)
        self.assertEqual(self.client.get('/api/usage/events/').status_code, 405)
        self.assertFalse(UsageEvent.objects.exists())

    def test_one_terminal_event_per_attempt_and_account_isolation(self):
        event = self.event()
        self.post([event])
        self.post([{**event, 'id': str(uuid.uuid4()), 'outcome': 'cancelled'}])
        self.assertEqual(UsageEvent.objects.get().outcome, 'success')
        other = User.objects.create_user(username='other_tester', password='password123')
        _, token = issue_token(other)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        self.post([{**event, 'id': str(uuid.uuid4())}])
        self.assertEqual(UsageEvent.objects.filter(user=other).count(), 1)
        self.assertEqual(UsageEvent.objects.filter(user=self.user).count(), 1)

    def test_all_phases_survive_out_of_order_delivery(self):
        finished = self.event(name='virtual_fitting', details={'source': 'virtual'})
        generated = {**finished, 'id': str(uuid.uuid4()), 'phase': 'generated', 'outcome': ''}
        started = {**finished, 'id': str(uuid.uuid4()), 'phase': 'started', 'outcome': '', 'duration_ms': None}
        self.assertEqual(self.post([finished, started, generated]).status_code, 200)
        self.assertEqual(UsageEvent.objects.count(), 3)

    def test_rejects_invalid_or_sensitive_payloads_atomically(self):
        invalid = [
            {'chat_text': 'private message'}, {'name': 'unknown'}, {'duration_ms': True}, {'duration_ms': -1},
            {'duration_ms': None}, {'phase': 'generated', 'outcome': ''}, {'outcome': 'failure'},
            {'error_code': 'private exception message'}, {'occurred_at': '2026-11-30T11:00:00'},
            {'details': {'source': 'wardrobe', 'photo': 'private bytes'}},
            {'details': {'source': 'chat', 'status': 'made_up'}}, {'attempt_id': '../other'},
        ]
        for change in invalid:
            with self.subTest(change=change):
                self.assertEqual(self.post([self.event(), self.event(**change)]).status_code, 400)
        self.assertFalse(UsageEvent.objects.exists())

    def test_clock_reset_does_not_invent_a_duration(self):
        event = self.event(duration_ms=None, details={'source': 'wardrobe', 'clock_reset': True})
        self.assertEqual(self.post([event]).status_code, 200)
        self.assertIsNone(UsageEvent.objects.get().duration_ms)

    def test_batch_and_body_limits(self):
        self.assertEqual(self.post([]).status_code, 400)
        self.assertEqual(self.post([self.event() for _ in range(26)]).status_code, 400)
        response = self.client.post('/api/usage/events/', ' ' * (32 * 1024 + 1), content_type='application/json')
        self.assertEqual(response.status_code, 413)

    def test_csv_export_is_filtered_and_has_no_username_or_text(self):
        self.post([self.event()])
        output = io.StringIO()
        call_command('export_usage', since='2000-01-01T00:00:00Z', stdout=output)
        rows = list(csv.DictReader(io.StringIO(output.getvalue())))
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['duration_ms'], '1234')
        self.assertNotIn(self.user.username, output.getvalue())
        self.assertNotIn('password', output.getvalue())
        empty = io.StringIO()
        call_command('export_usage', until='2000-01-01T00:00:00Z', stdout=empty)
        self.assertEqual(len(list(csv.DictReader(io.StringIO(empty.getvalue())))), 0)

    def test_metrics_failure_does_not_break_confirmed_operation(self):
        with patch('apps.usage.recording.UsageEvent.objects.create', side_effect=RuntimeError('unavailable')):
            with self.assertLogs('apps.usage.recording', level='ERROR'):
                record_outfit_event(self.user, 'outfit_save', source='home')


class OutfitUsageTests(UsageFixture):
    def setUp(self):
        super().setUp()
        self.top = Garment.objects.create(user=self.user, image='garments/top.jpg', saved=True,
                                          attributes={'name': 'same name', 'category': 'top'})
        self.bottom = Garment.objects.create(user=self.user, image='garments/bottom.jpg', saved=True,
                                             attributes={'name': 'same name', 'category': 'bottom'})
        self.ids = [str(self.top.id), str(self.bottom.id)]

    def save_outfit(self, ids=None, **changes):
        return self.client.post('/api/outfits/', json.dumps({'garment_ids': ids or self.ids, **changes}), content_type='application/json')

    def test_save_duplicates_use_ids_and_distinguish_chat_retries(self):
        self.assertEqual(self.save_outfit().status_code, 201)
        self.assertEqual(self.save_outfit(self.ids[::-1]).status_code, 201)
        events = list(UsageEvent.objects.filter(name='outfit_save').order_by('received_at'))
        self.assertEqual([e.details['already_saved'] for e in events], [False, True])
        self.assertEqual(events[-1].details['duplicate_count'], 0)
        self.assertEqual(Outfit.objects.filter(user=self.user, saved=True).count(), 1)
        another = Garment.objects.create(user=self.user, image='garments/top2.jpg', saved=True,
                                         attributes={'name': 'same name', 'category': 'top'})
        self.save_outfit([str(another.id), self.ids[1]])
        self.assertFalse(UsageEvent.objects.filter(name='outfit_save').latest('received_at').details['already_saved'])
        for _ in range(2):
            self.save_outfit(source='chat', context={'chat_save_id': 'retry-id'})
        self.assertEqual(UsageEvent.objects.filter(name='outfit_save', details__source='chat').count(), 1)

    def test_unsave_and_rename_are_not_save_events(self):
        row = self.save_outfit().json()
        for changes in ({'saved': False}, {'name': 'renamed'}):
            self.client.put(f'/api/outfits/{row["id"]}/', json.dumps(changes), content_type='application/json')
        self.assertEqual(UsageEvent.objects.filter(name='outfit_save').count(), 1)
        self.client.put(f'/api/outfits/{row["id"]}/', json.dumps({'saved': True}), content_type='application/json')
        self.assertFalse(UsageEvent.objects.filter(name='outfit_save').latest('received_at').details['already_saved'])

    def test_home_save_keeps_deduplication_and_records_existing_combination(self):
        first = self.save_outfit().json()
        second = self.save_outfit(self.ids[::-1]).json()
        response = self.client.put(f'/api/outfits/{first["id"]}/', json.dumps({'saved': True}), content_type='application/json')
        self.assertEqual(response.status_code, 200)
        saved_ids = list(Outfit.objects.filter(user=self.user, saved=True).values_list('id', flat=True))
        self.assertEqual(saved_ids, [uuid.UUID(first['id'])])
        self.assertFalse(Outfit.objects.get(pk=second['id']).saved)
        event = UsageEvent.objects.filter(name='outfit_save').latest('received_at')
        self.assertEqual(event.details, {'source': 'user', 'already_saved': True, 'total_count': 1, 'duplicate_count': 0})

    def test_recommendation_duplicates_include_previous_and_same_batch_combinations(self):
        candidate = {'items': [{'id': f'w:{self.top.id}', 'slot': '상의'}, {'id': f'w:{self.bottom.id}', 'slot': '하의'}],
                     'total': 80, 'components': {}, 'reasons': []}
        result = {'outfits': [candidate, candidate], 'notes': [], 'band': {'label': 'mild'}}
        with patch('apps.outfits.views._calculate', return_value=(result, {})):
            response = self.client.post('/api/outfits/recommend/', json.dumps({
                'seen_keys': ['+'.join(f'w:{gid}' for gid in self.ids[::-1])]}), content_type='application/json')
        self.assertEqual(response.status_code, 200)
        event = UsageEvent.objects.get(name='outfit_recommendation')
        self.assertEqual((event.details['total_count'], event.details['duplicate_count']), (2, 2))
        self.assertEqual(Outfit.objects.count(), 2)

    def test_empty_recommendation_history_is_valid(self):
        candidate = {'items': [{'id': f'w:{self.top.id}', 'slot': '상의'}, {'id': f'w:{self.bottom.id}', 'slot': '하의'}],
                     'total': 80, 'components': {}, 'reasons': []}
        result = {'outfits': [candidate], 'notes': [], 'band': {'label': 'mild'}}
        for data in ({}, {'seen_keys': None}):
            with self.subTest(data=data), patch('apps.outfits.views._calculate', return_value=(result, {})):
                response = self.client.post('/api/outfits/recommend/', json.dumps(data), content_type='application/json')
                self.assertEqual(response.status_code, 200)
        self.assertEqual([event.details['duplicate_count'] for event in UsageEvent.objects.all()], [0, 0])

    def test_chat_recommendation_uses_the_same_id_combination_metric(self):
        key = '+'.join(sorted(f'w:{gid}' for gid in self.ids))
        with patch('apps.outfits.chat.read', return_value=()), patch('apps.outfits.chat.process',
                return_value=JsonResponse({'status': 'updated', 'outfit': {'key': key}})):
            response = self.client.post('/api/outfits/chat/', json.dumps({'seen_keys': ['+'.join(key.split('+')[::-1])]}),
                                        content_type='application/json')
        self.assertEqual(response.status_code, 200)
        event = UsageEvent.objects.get(name='outfit_recommendation')
        self.assertEqual(event.details, {'source': 'chat', 'total_count': 1, 'duplicate_count': 1})
