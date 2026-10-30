#!/usr/bin/env python3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import notice_compatibility as compatibility

class CompatibilityTests(unittest.TestCase):
    def hit(self, scope='Mariners operating in Fictional Basin', period='With effect from 14 Apr 2099 to 13 Oct 2099.', activities=('dredging',), locations=('Gul Basin',)):
        return {'facts': {'scope': scope, 'effective_period': period, 'supersedes': '8 of 2099'}, 'document_activities': activities, 'document_locations': locations}

    def test_explicit_activity_mismatch_is_rejected(self):
        result = compatibility.assess('bulk carrier anchoring at Gul Basin', self.hit())
        self.assertEqual(result['criteria']['activity'], compatibility.INCOMPATIBLE)
        self.assertEqual(result['decision'], 'REJECT')

    def test_period_is_checked_without_assumption(self):
        self.assertEqual(compatibility.assess('Gul Basin dredging on 2099-11-01', self.hit())['criteria']['effective_period'], compatibility.INCOMPATIBLE)
        self.assertEqual(compatibility.assess('Gul Basin dredging on 2099-06-01', self.hit())['criteria']['effective_period'], compatibility.COMPATIBLE)

    def test_missing_document_evidence_is_unknown(self):
        result = compatibility.assess('closure at Gul Basin on 2099-06-01', self.hit(period=None, activities=(), locations=()))
        self.assertEqual(result['criteria']['effective_period'], compatibility.UNKNOWN)
        self.assertEqual(result['decision'], 'CANDIDATE_FOR_REVIEW')

    def test_vessel_causation_question_is_unsupported(self):
        self.assertTrue(compatibility.unsupported_conclusion('Did this notice cause vessel TEST ETA delay?'))

    def test_scope_location_fallback_and_directional_supersession(self):
        hit = self.hit(locations=(), scope='Mariners near Gul Basin')
        self.assertEqual(compatibility.assess('Gul Basin dredging', hit)['criteria']['location'], compatibility.COMPATIBLE)
        self.assertEqual(compatibility.assess('What does PMN 9 supersede?', {**hit, 'facts': {**hit['facts'], 'notice_identifier': '9 OF 2099'}})['criteria']['supersession'], compatibility.COMPATIBLE)
        self.assertEqual(compatibility.assess('Which notice amended PMN 9?', hit)['criteria']['supersession'], compatibility.INCOMPATIBLE)

    def test_date_token_is_not_treated_as_notice_number_for_supersession(self):
        result = compatibility.assess('Gul Basin dredging on 2099-06-01; mention supersession', self.hit())
        self.assertEqual(result['criteria']['supersession'], compatibility.UNKNOWN)

if __name__ == '__main__':
    unittest.main()
