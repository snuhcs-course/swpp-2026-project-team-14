import json
from unittest.mock import patch

from django.test import TestCase

from apps.users.auth import issue_token
from apps.users.models import User
from apps.wardrobe.models import Garment
from recommendation import chat_intent
from .models import Outfit
from .tests import BODY, garment


class ChatApiTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user('chat_owner', 'test-password')
        _, token = issue_token(self.user)
        self.client.defaults['HTTP_AUTHORIZATION'] = f'Bearer {token}'
        self.top = garment(self.user, '검정 티', 'top', 'tshirt', '#202020', chest_width_half=55, total_length=65, sleeve_length=20)
        self.bright = garment(self.user, '흰 셔츠', 'top', 'shirt', '#FFFFFF', chest_width_half=55, total_length=68, sleeve_length=60)
        self.bottom = garment(self.user, '슬랙스', 'bottom', 'slacks', '#202020', hip_width_half=55, inseam=72)
        self.jeans = garment(self.user, '청바지', 'bottom', 'jeans', '#3975C6', hip_width_half=55, inseam=72)
        self.outer = garment(self.user, '코트', 'outerwear', 'coat', '#795548', chest_width_half=60, total_length=100)
        self.ids = [str(self.top.id), str(self.bottom.id)]

    def post(self, **changes):
        payload = dict(body=BODY, temp=16, garment_ids=self.ids, locked_ids=[], directions=['brighter'])
        return self.client.post('/api/outfits/chat/', json.dumps(payload | changes), content_type='application/json')

    def test_button_locks_and_preview_do_not_save(self):
        with patch('recommendation.chat_intent.parse', side_effect=AssertionError('buttons must not call AI')):
            response = self.post(locked_ids=[str(self.bottom.id)])
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data['status'], 'updated')
        self.assertEqual({i['garment_id'] for i in data['outfit']['items'] if i['slot'] == 'bottom'}, {str(self.bottom.id)})
        self.assertIn(str(self.bright.id), data['changed_ids'])
        self.assertFalse(Outfit.objects.exists())

    def test_locked_outer_survives_hot_weather_with_complete_outfit(self):
        response = self.post(garment_ids=self.ids + [str(self.outer.id)], locked_ids=[str(self.outer.id), str(self.bottom.id)], temp=30)
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data['status'], 'updated')
        self.assertEqual({i['slot'] for i in data['outfit']['items']}, {'outerwear', 'top', 'bottom'})
        self.assertIn(str(self.outer.id), {i['garment_id'] for i in data['outfit']['items']})

    def test_free_text_requests_unlock_before_change(self):
        parsed = chat_intent.empty() | {'change_slots': ['bottom'], 'wants': [{'slot': 'bottom', 'want': 'casual'}]}
        with patch('recommendation.chat_intent.parse', return_value=(parsed, 'gemini', None)):
            data = self.post(locked_ids=[str(self.bottom.id)], directions=[], text='바지도 바꿔줘').json()
        self.assertEqual(data['status'], 'unlock')
        self.assertEqual(data['unlock_ids'], [str(self.bottom.id)])
        self.assertIsNone(data['outfit'])

    def test_text_keep_applies_to_this_edit_without_creating_a_lock(self):
        parsed = chat_intent.empty() | {'change_slots': ['top'], 'keep_slots': ['bottom'],
                                      'wants': [{'slot': 'top', 'want': 'brighter'}]}
        with patch('recommendation.chat_intent.parse', return_value=(parsed, 'gemini', None)):
            data = self.post(directions=[], text='바지는 그대로, 상의만 밝게').json()
        self.assertEqual(data['status'], 'updated')
        self.assertEqual(data['locked_ids'], [])
        self.assertEqual({i['garment_id'] for i in data['outfit']['items'] if i['slot'] == 'bottom'}, {str(self.bottom.id)})
        self.assertEqual(data['changed_ids'], [str(self.bright.id)])

    def test_all_locked_or_no_matching_clothes_never_unlock(self):
        data = self.post(locked_ids=self.ids).json()
        self.assertEqual(data['status'], 'clarify')
        data = self.post(locked_ids=[str(self.bottom.id)], directions=['colorful']).json()
        self.assertEqual(data['status'], 'unchanged')
        self.assertIsNone(data['outfit'])
        self.assertEqual(data['locked_ids'], [str(self.bottom.id)])

    def test_missing_garment_and_invalid_requests(self):
        self.assertEqual(self.post(locked_ids=[str(self.bright.id)]).status_code, 400)
        self.assertEqual(self.post(directions=['invented']).status_code, 400)
        self.assertEqual(self.post(text='a' * 401).status_code, 400)
        self.top.delete()
        self.assertEqual(self.post().status_code, 422)

    def test_model_failure_is_retryable_and_does_not_modify_database(self):
        with patch('recommendation.chat_intent.parse', side_effect=chat_intent.ChatModelError):
            response = self.post(text='상의만 화사하게', directions=[])
        self.assertEqual(response.status_code, 503)
        self.assertFalse(Outfit.objects.exists())
        self.assertEqual(Garment.objects.count(), 5)

    def test_negated_colour_is_not_selected(self):
        parsed = chat_intent.empty() | {'change_slots': ['top'], 'avoid_colors': ['블랙']}
        with patch('recommendation.chat_intent.parse', return_value=(parsed, 'gemini', None)):
            data = self.post(text='검정 상의는 싫어', directions=[]).json()
        self.assertEqual(data['status'], 'updated')
        self.assertNotIn(str(self.top.id), {i['garment_id'] for i in data['outfit']['items']})

    def test_save_retry_returns_same_saved_chat_outfit(self):
        payload = {'garment_ids': self.ids, 'source': 'chat', 'context': {'chat_save_id': 'retry-token'}}
        first = self.client.post('/api/outfits/', json.dumps(payload), content_type='application/json')
        second = self.client.post('/api/outfits/', json.dumps(payload), content_type='application/json')
        self.assertEqual(first.status_code, 201)
        self.assertEqual(second.status_code, 200)
        self.assertEqual(first.json()['id'], second.json()['id'])
        self.assertEqual(Outfit.objects.count(), 1)

    def test_account_chat_cannot_use_another_accounts_clothes(self):
        other = User.objects.create_user('another_chat_owner', 'test-password')
        other_top = garment(other, '다른 계정 티', 'top', 'tshirt', '#FFFFFF')
        other_bottom = garment(other, '다른 계정 바지', 'bottom', 'jeans', '#3975C6')
        response = self.post(garment_ids=[str(other_top.id), str(other_bottom.id)])
        self.assertEqual(response.status_code, 422)

    def test_chat_requires_login_and_guest_preview_is_removed(self):
        self.client.defaults.pop('HTTP_AUTHORIZATION')
        self.assertEqual(self.post().status_code, 401)
        self.assertEqual(self.client.post('/api/outfits/preview/chat/').status_code, 404)
        self.assertFalse(Outfit.objects.exists())

    def test_schema_rejects_invented_or_conflicting_values(self):
        with self.assertRaises(ValueError):
            chat_intent.validate(chat_intent.empty() | {'change_slots': ['shoes']})
        with self.assertRaises(ValueError):
            chat_intent.validate(chat_intent.empty() | {'change_slots': ['top'], 'keep_slots': ['top']})

    def test_named_garment_is_used_and_unasked_slots_stay(self):
        parsed = chat_intent.empty() | {'target_garment_ids': [str(self.jeans.id)]}
        with patch('recommendation.chat_intent.parse', return_value=(parsed, 'gemini', None)):
            data = self.post(text='하의를 청바지로 바꿔줘', directions=[]).json()
        self.assertEqual(data['status'], 'updated')
        self.assertEqual({i['garment_id'] for i in data['outfit']['items']}, {str(self.top.id), str(self.jeans.id)})

    def test_removing_outerwear_does_not_replace_other_slots(self):
        parsed = chat_intent.empty() | {'change_slots': ['outerwear'], 'remove_outerwear': True}
        with patch('recommendation.chat_intent.parse', return_value=(parsed, 'gemini', None)):
            data = self.post(garment_ids=self.ids + [str(self.outer.id)], text='겉옷은 빼줘', directions=[]).json()
        self.assertEqual(data['status'], 'updated')
        self.assertEqual({i['garment_id'] for i in data['outfit']['items']}, set(self.ids))

    def test_model_inferred_keep_does_not_relock_a_manually_unlocked_item(self):
        parsed = chat_intent.empty() | {'change_slots': ['top'], 'keep_slots': ['bottom'],
                                      'target_garment_ids': [str(self.bright.id)]}
        history = [{'role': 'user', 'text': 'I want skirts'},
                   {'role': 'assistant', 'text': '현재 치마가 없어요.'}]
        with patch('recommendation.chat_intent.parse', return_value=(parsed, 'gemini', None)):
            data = self.post(garment_ids=self.ids + [str(self.outer.id)],
                locked_ids=[str(self.outer.id)], text="ok I don't want knit", directions=[], history=history).json()
        self.assertEqual(data['status'], 'updated')
        self.assertEqual(data['locked_ids'], [str(self.outer.id)])
        current_ids = [i['garment_id'] for i in data['outfit']['items']]
        # The next turn can change the bottom with no unlock confirmation.
        parsed = chat_intent.empty() | {'target_garment_ids': [str(self.jeans.id)]}
        with patch('recommendation.chat_intent.parse', return_value=(parsed, 'gemini', None)):
            following = self.post(garment_ids=current_ids, locked_ids=data['locked_ids'],
                text='하의를 청바지로 바꿔줘', directions=[]).json()
        self.assertEqual(following['status'], 'updated')
        self.assertEqual(following['locked_ids'], [str(self.outer.id)])
        self.assertIn(str(self.jeans.id), following['changed_ids'])
