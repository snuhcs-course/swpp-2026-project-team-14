import json
from unittest.mock import patch

from django.db import DatabaseError
from django.test import TestCase

from .models import ProbeItem


class ConnectionProbeTests(TestCase):
    def test_health_queries_mysql(self):
        response = self.client.get('/api/dev/health/')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['database'], 'mysql')

    def test_database_failure_returns_unavailable(self):
        with patch('probe.views.connection.cursor', side_effect=DatabaseError('private detail')):
            response = self.client.get('/api/dev/health/')
        self.assertEqual(response.status_code, 503)
        self.assertNotIn('private detail', response.content.decode())

    def test_create_and_read_unicode_item(self):
        response = self.client.post('/api/dev/items/', {'name': '  흰색 셔츠  '}, content_type='application/json')
        self.assertEqual(response.status_code, 201)
        self.assertEqual(ProbeItem.objects.get().name, '흰색 셔츠')
        rows = self.client.get('/api/dev/items/').json()['results']
        self.assertEqual(rows[0]['id'], response.json()['id'])

    def test_invalid_payloads_do_not_write(self):
        for payload in [{}, [], {'name': 42}, {'name': ' '}, {'name': 'x' * 51}, {'name': 'ok', 'owner': 1}]:
            with self.subTest(payload=payload):
                response = self.client.post('/api/dev/items/', json.dumps(payload), content_type='application/json')
                self.assertEqual(response.status_code, 400)
        self.assertEqual(ProbeItem.objects.count(), 0)

    def test_malformed_json_and_wrong_content_type(self):
        self.assertEqual(self.client.post('/api/dev/items/', '{', content_type='application/json').status_code, 400)
        self.assertEqual(self.client.post('/api/dev/items/', {'name': 'x'}).status_code, 415)

    def test_unsupported_methods(self):
        self.assertEqual(self.client.delete('/api/dev/items/').status_code, 405)
