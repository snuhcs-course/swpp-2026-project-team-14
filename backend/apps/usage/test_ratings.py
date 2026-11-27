# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, review pending (Kihwan Kim)
import base64
import csv
import io
import json
import uuid
from datetime import timedelta
from unittest.mock import patch

from django.core.management import call_command
from django.db import IntegrityError, transaction
from django.db.models.query import QuerySet
from django.test import override_settings
from django.utils import timezone
from PIL import Image

from apps.fittings.generation import GeneratedImage
from apps.fittings.jobs import Jobs
from apps.users.auth import issue_token
from apps.users.models import User
from apps.wardrobe.models import Garment
from apps.wardrobe.tests import attributes

from .models import Metric, NoSeen, RatingTask, UsageEvent
from .ratings import COOLDOWN, should_prompt
from .tests import UsageFixture

FITTING = RatingTask.VIRTUAL_FITTING


class RatingFixture(UsageFixture):
    def rating(self, task=FITTING, submitted=True, wait=3.5, quality=4, convenience=0, no_seen=False, **changes):
        details = {'task': task, 'submitted': submitted, 'wait': wait, 'quality': quality,
                   'convenience': convenience, 'no_seen': no_seen}
        return {**self.event(name='task_rating', duration_ms=None, details=details), **changes}


class PromptDecisionTests(RatingFixture):
    def test_first_use_prompts_then_cooldown_then_prompts_again(self):
        self.assertTrue(should_prompt(self.user, FITTING))
        self.assertEqual(self.post([self.rating()]).status_code, 200)
        self.assertFalse(should_prompt(self.user, FITTING))
        # 다른 작업은 독립적으로 판단한다.
        self.assertTrue(should_prompt(self.user, RatingTask.RECOMMENDATION))
        Metric.objects.update(rated_at=timezone.now() - COOLDOWN - timedelta(seconds=1))
        self.assertTrue(should_prompt(self.user, FITTING))

    def test_no_seen_is_checked_first_and_is_per_task(self):
        NoSeen.objects.create(user=self.user, task=FITTING)
        self.assertFalse(should_prompt(self.user, FITTING))
        self.assertTrue(should_prompt(self.user, RatingTask.GARMENT_REGISTRATION))

    def test_other_accounts_do_not_affect_the_decision(self):
        other = User.objects.create_user(username='other_rater', password='password123')
        Metric.objects.create(user=other, task=FITTING, wait_score=1, quality_score=1, convenience_score=1,
                              rated_at=timezone.now())
        NoSeen.objects.create(user=other, task=RatingTask.RECOMMENDATION)
        self.assertTrue(should_prompt(self.user, FITTING))
        self.assertTrue(should_prompt(self.user, RatingTask.RECOMMENDATION))

    def test_decision_failure_never_prompts_and_is_logged(self):
        with patch('apps.usage.ratings.NoSeen.objects.filter', side_effect=RuntimeError('db down')):
            with self.assertLogs('apps.usage.ratings', level='ERROR'):
                self.assertFalse(should_prompt(self.user, FITTING))


class RatingCollectionTests(RatingFixture):
    def test_submit_appends_a_row_with_half_step_scores(self):
        first = self.rating(wait=3.5, quality=4, convenience=0)
        later = self.rating(wait=5, quality=0.5, convenience=2.5,
                            occurred_at=(timezone.now() + timedelta(seconds=1)).isoformat())
        response = self.post([first, later])
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['accepted'], [first['id'], later['id']])
        rows = list(Metric.objects.order_by('rated_at').values_list('user', 'task', 'wait_score', 'quality_score',
                                                                     'convenience_score'))
        self.assertEqual(rows, [(self.user.pk, FITTING, 3.5, 4.0, 0.0), (self.user.pk, FITTING, 5.0, 0.5, 2.5)])
        # 별점은 측정 이벤트 테이블에 섞이지 않는다.
        self.assertFalse(UsageEvent.objects.exists())

    def test_lost_response_retry_does_not_duplicate_the_rating(self):
        event = self.rating(no_seen=True)
        for _ in range(2):
            self.assertEqual(self.post([event]).status_code, 200)
        self.assertEqual(Metric.objects.count(), 1)
        self.assertEqual(NoSeen.objects.count(), 1)

    def test_overlapping_retry_recovers_from_database_conflict(self):
        event = self.rating(no_seen=True)
        original_get = QuerySet.get
        raced = False

        def get_with_competing_insert(queryset, *args, **kwargs):
            nonlocal raced
            if queryset.model is Metric and not raced:
                try:
                    return original_get(queryset, *args, **kwargs)
                except Metric.DoesNotExist:
                    # Another request commits after the initial lookup misses, before INSERT.
                    raced = True
                    Metric.objects.create(user=self.user, event_id=event['id'], task=FITTING,
                                          wait_score=3.5, quality_score=4, convenience_score=0,
                                          rated_at=timezone.now())
                    raise
            return original_get(queryset, *args, **kwargs)

        with patch.object(QuerySet, 'get', get_with_competing_insert):
            response = self.post([event])
        self.assertTrue(raced)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['accepted'], [event['id']])
        self.assertEqual(Metric.objects.count(), 1)
        self.assertEqual(NoSeen.objects.count(), 1)

    def test_database_rejects_duplicate_event_for_the_same_user(self):
        event = self.rating()
        self.assertEqual(self.post([event]).status_code, 200)
        values = Metric.objects.values().get()
        values.pop('id')
        with self.assertRaises(IntegrityError), transaction.atomic():
            Metric.objects.create(**values)
        other = User.objects.create_user(username='independent_rater', password='password123')
        Metric.objects.create(**{**values, 'user_id': other.pk})
        self.assertEqual(Metric.objects.count(), 2)

    def test_distinct_events_at_the_same_time_are_preserved(self):
        first = self.rating()
        second = self.rating(occurred_at=first['occurred_at'])
        self.assertEqual(self.post([first, second]).status_code, 200)
        self.assertEqual(Metric.objects.count(), 2)

    def test_legacy_ratings_without_event_ids_are_preserved(self):
        values = dict(user=self.user, task=FITTING, wait_score=3.5, quality_score=4,
                      convenience_score=0, rated_at=timezone.now())
        first = Metric.objects.create(**values)
        second = Metric.objects.create(**values)
        self.assertIsNone(first.event_id)
        self.assertIsNone(second.event_id)
        self.assertNotEqual(first.pk, second.pk)

    def test_four_responses(self):
        cases = [
            # (submitted, no_seen) → (metrics 행 추가, no_seen 행 추가)
            ((True, False), (1, 0)),
            ((True, True), (1, 1)),
            ((False, False), (0, 0)),
            ((False, True), (0, 1)),
        ]
        for (submitted, no_seen), expected in cases:
            with self.subTest(submitted=submitted, no_seen=no_seen):
                Metric.objects.all().delete()
                NoSeen.objects.all().delete()
                self.assertEqual(self.post([self.rating(submitted=submitted, no_seen=no_seen)]).status_code, 200)
                self.assertEqual((Metric.objects.count(), NoSeen.objects.count()), expected)

    def test_skip_without_checkbox_keeps_prompting(self):
        self.post([self.rating(submitted=False)])
        self.assertTrue(should_prompt(self.user, FITTING))

    def test_invalid_ratings_are_rejected_atomically(self):
        valid = self.rating()
        invalid = [
            self.rating(wait=0.3), self.rating(quality=5.5), self.rating(convenience=-0.5),
            self.rating(wait=True), self.rating(wait='3'), self.rating(wait=None),
            self.rating(task='chat_request'), self.rating(submitted='yes'), self.rating(no_seen=1),
            {**self.rating(), 'details': {**self.rating()['details'], 'extra': 1}},
            {**self.rating(), 'details': {k: v for k, v in self.rating()['details'].items() if k != 'no_seen'}},
            self.rating(phase='started'), self.rating(outcome='failure', error_code='X'), self.rating(duration_ms=10),
            self.rating(occurred_at='2026-01-01T00:00:00'),
            self.rating(occurred_at=(timezone.now() + timedelta(hours=1)).isoformat()),
        ]
        for event in invalid:
            with self.subTest(event=event['details']):
                response = self.post([valid, event])
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.json()['error'], 'INVALID_USAGE_EVENT')
        self.assertFalse(Metric.objects.exists())
        self.assertFalse(NoSeen.objects.exists())

    def test_requires_authentication(self):
        self.client.defaults.clear()
        self.assertEqual(self.post([self.rating()]).status_code, 401)
        self.assertFalse(Metric.objects.exists())

    def test_ratings_survive_account_deletion_but_no_seen_does_not(self):
        self.post([self.rating(no_seen=True)])
        user_id = self.user.pk
        self.user.delete()
        self.assertEqual(Metric.objects.get().user_id, user_id)
        self.assertFalse(NoSeen.objects.exists())

    def test_csv_export_has_user_id_task_scores_and_kst_time(self):
        self.post([self.rating(occurred_at='2026-11-20T01:30:00+00:00')])
        output = io.StringIO()
        call_command('export_metrics', since='2026-11-20T00:00:00+09:00', stdout=output)
        rows = list(csv.DictReader(io.StringIO(output.getvalue())))
        self.assertEqual(rows, [{'user_id': str(self.user.pk), 'task': 'virtual_fitting', 'wait_score': '3.5',
                                 'quality_score': '4.0', 'convenience_score': '0.0',
                                 'rated_at': '2026-11-20T10:30:00+09:00'}])
        self.assertNotIn(self.user.username, output.getvalue())
        empty = io.StringIO()
        call_command('export_metrics', until='2026-11-20T00:00:00+09:00', stdout=empty)
        self.assertEqual(list(csv.DictReader(io.StringIO(empty.getvalue()))), [])


class RatingPromptFlagTests(RatingFixture):
    """기존 API 응답에 rating_prompt가 붙고, 평가 후에는 False가 되는지 확인한다."""

    def submit(self, task):
        self.post([self.rating(task=task)])

    def test_recommendation_response(self):
        top = Garment.objects.create(user=self.user, image='garments/top.jpg', saved=True, attributes={'category': 'top'})
        bottom = Garment.objects.create(user=self.user, image='garments/bottom.jpg', saved=True,
                                        attributes={'category': 'bottom'})
        candidate = {'items': [{'id': f'w:{top.id}', 'slot': '상의'}, {'id': f'w:{bottom.id}', 'slot': '하의'}],
                     'total': 80, 'components': {}, 'reasons': []}
        result = {'outfits': [candidate], 'notes': [], 'band': {'label': 'mild'}}

        def recommend():
            with patch('apps.outfits.views._calculate', return_value=(result, {})):
                response = self.client.post('/api/outfits/recommend/', '{}', content_type='application/json')
            self.assertEqual(response.status_code, 200)
            return response.json()['rating_prompt']

        self.assertTrue(recommend())
        self.submit(RatingTask.RECOMMENDATION)
        self.assertFalse(recommend())

    def test_garment_save_response(self):
        garment = Garment.objects.create(user=self.user, image='garments/top.jpg', attributes=attributes())
        path = f'/api/wardrobe/items/{garment.pk}/'
        payload = json.dumps({'attributes': attributes(), 'dimensions': None, 'notes': ''})
        saved = self.client.put(path, payload, content_type='application/json')
        self.assertEqual(saved.status_code, 200, saved.content)
        self.assertTrue(saved.json()['rating_prompt'])
        self.assertEqual(saved.json()['id'], str(garment.pk))
        self.submit(RatingTask.GARMENT_REGISTRATION)
        self.assertFalse(self.client.put(path, payload, content_type='application/json').json()['rating_prompt'])
        # 조회(GET)에는 붙이지 않는다.
        self.assertNotIn('rating_prompt', self.client.get(path).json())


@override_settings(OPENROUTER_API_KEY='test-only')
class FittingRatingFlagTests(RatingFixture):
    def setUp(self):
        super().setUp()
        output = io.BytesIO()
        Image.new('RGB', (20, 30), 'navy').save(output, 'JPEG')
        encoded = base64.b64encode(output.getvalue()).decode()
        self.payload = {'consent': True, 'person': encoded, 'garments': [{'category': 'tops', 'image': encoded}]}
        self.jobs = Jobs(lambda *args: GeneratedImage(output.getvalue(), '0.06'), start=False)
        mocked = patch('apps.fittings.views.get_jobs', return_value=self.jobs)
        mocked.start()
        self.addCleanup(mocked.stop)
        self.addCleanup(self.jobs.close)
        # The test photo is a plain colour: where the segmentation model is installed, the person check
        # (apps.fittings.person, tested in apps.fittings) would rightly refuse it.
        person = patch('apps.fittings.person.check', return_value=None)
        person.start()
        self.addCleanup(person.stop)

    def test_flag_only_on_completed_job(self):
        uploaded = self.client.post('/api/fittings/jobs/', json.dumps(self.payload), content_type='application/json').json()
        path = f'/api/fittings/jobs/{uploaded["id"]}/'
        headers = {'HTTP_X_FITTING_TOKEN': uploaded['token']}
        self.assertNotIn('rating_prompt', self.client.get(path, **headers).json())
        self.jobs.run_next()
        self.assertTrue(self.client.get(path, **headers).json()['rating_prompt'])
        self.post([self.rating(task=FITTING)])
        self.assertFalse(self.client.get(path, **headers).json()['rating_prompt'])
