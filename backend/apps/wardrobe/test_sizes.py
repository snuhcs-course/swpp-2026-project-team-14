# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
from django.test import SimpleTestCase

from .models import Garment
from .sizes import length_cm


def garment(category, subcategory, dimensions=None, labels=None):
    return Garment(attributes={'category': category, 'subcategory': subcategory}, dimensions=dimensions, labels=labels)


class GarmentLengthTests(SimpleTestCase):
    def test_a_measured_length_is_trusted_first(self):
        measured = {'unit': 'cm', 'total_length': {'value': 71.6, 'source': 'arcore_assisted'}}
        self.assertEqual(length_cm(garment('top', 'tshirt', measured, {'length': '크롭'})), 72)

    def test_a_length_label_is_used_before_the_kind(self):
        self.assertEqual(length_cm(garment('bottom', 'skirt', labels={'length': '미니'})), 42)
        self.assertEqual(length_cm(garment('outerwear', 'jacket', labels={'length': '롱'})), 105)

    def test_typical_lengths_keep_a_coat_longer_than_a_tee_and_trousers_full_length(self):
        coat, tee, slacks = (length_cm(garment(*k)) for k in
                             (('outerwear', 'coat'), ('top', 'tshirt'), ('bottom', 'slacks')))
        self.assertGreater(coat, tee * 1.4)
        self.assertGreaterEqual(slacks, 95)

    def test_implausible_measurements_and_rough_estimates_are_ignored(self):
        self.assertEqual(length_cm(garment('top', 'tshirt', {'unit': 'cm', 'total_length': {'value': 3}})), 68)
        self.assertIsNone(length_cm(garment('shoes', 'sneakers')))
