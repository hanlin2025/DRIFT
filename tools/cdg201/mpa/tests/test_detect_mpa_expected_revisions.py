import unittest
from detect_mpa_expected_revisions import analyse, dedupe_run, match_runs


def obs(run, expected, dataset="due_to_arrive", imo="1234567", query="2026-10-26", origin="A", destination="B"):
    return {"run_id":run,"dataset":dataset,"imo":imo,"vessel_name":"Test vessel","call_sign":"C","from":origin,"to":destination,
            "expected":expected,"queried_date":query,"retrieval_utc":"2026-10-26T00:00:00+00:00",
            "raw_file":run+".json","manifest":run+".manifest.json"}


def slice_(run, dataset="due_to_arrive", date="2026-10-26", valid=True):
    return {"run_id":run,"dataset":dataset,"queried_date":date,"valid":valid,
            "retrieval_started_utc":"2026-10-26T00:00:00+00:00" if run=="r1" else "2026-10-26T01:00:00+00:00"}


class TestRevision(unittest.TestCase):
    def test_duplicate_within_run_is_not_revision(self):
        self.assertEqual(len(dedupe_run([obs("r1","2026-10-26 12:00"),obs("r1","2026-10-26 12:00")])),1)

    def test_unchanged_successive_runs(self):
        cycles, revisions, *_ = analyse([slice_("r1"),slice_("r2")],[obs("r1","2026-10-26 12:00"),obs("r2","2026-10-26 12:00")])
        self.assertEqual(len(cycles),2); self.assertFalse(revisions)

    def test_changed_eta_crosses_calendar_date(self):
        slices=[slice_("r1",date="2026-10-26"),slice_("r1",date="2026-10-27"),slice_("r2",date="2026-10-26"),slice_("r2",date="2026-10-27")]
        _, revisions, *_ = analyse(slices,[obs("r1","2026-10-26 23:00"),obs("r2","2026-10-27 01:00",query="2026-10-27")])
        self.assertEqual(len(revisions),1); self.assertEqual(revisions[0]["revision_minutes"],120)

    def test_cross_date_change_needs_both_date_slices(self):
        _, revisions, _, _, insufficient = analyse([slice_("r1"),slice_("r2",date="2026-10-27")],[obs("r1","2026-10-26 23:00"),obs("r2","2026-10-27 01:00",query="2026-10-27")])
        self.assertFalse(revisions); self.assertEqual(insufficient[0]["reason"],"cross_date_coverage_incomplete")

    def test_valid_arrivals_compare_when_departures_invalid(self):
        slices=[slice_("r1"),slice_("r1","due_to_depart",valid=False),slice_("r2"),slice_("r2","due_to_depart",valid=False)]
        _, revisions, *_ = analyse(slices,[obs("r1","2026-10-26 12:00"),obs("r2","2026-10-26 13:00")])
        self.assertEqual(len(revisions),1); self.assertEqual(revisions[0]["kind"],"confirmed_observed_revision")

    def test_valid_departure_feed_is_compared_independently(self):
        slices=[slice_("r1","due_to_depart"),slice_("r2","due_to_depart")]
        _, revisions, *_ = analyse(slices,[obs("r1","2026-10-26 12:00",dataset="due_to_depart",origin="",destination=""),obs("r2","2026-10-26 13:00",dataset="due_to_depart",origin="",destination="")])
        self.assertEqual(len(revisions),1); self.assertEqual(revisions[0]["kind"],"observed_timestamp_change_low_context")

    def test_valid_empty_slice_is_successful_coverage(self):
        slices=[slice_("r1",date="2026-10-25"),slice_("r1"),slice_("r2",date="2026-10-25"),slice_("r2")]
        _, revisions, *_ = analyse(slices,[obs("r1","2026-10-26 12:00"),obs("r2","2026-10-26 13:00")])
        self.assertEqual(len(revisions),1)

    def test_missing_coverage_blocks_comparison(self):
        old,new=obs("r1","2026-10-26 12:00"),obs("r2","2026-10-26 13:00")
        revisions,_,_,insufficient=match_runs([old],[new],{("r1","due_to_arrive","2026-10-26"):False,("r2","due_to_arrive","2026-10-26"):True})
        self.assertFalse(revisions); self.assertEqual(insufficient[0]["reason"],"previous_slice_invalid")

    def test_multiple_visits_remain_one_to_one(self):
        old=[obs("r1","2026-10-26 12:00"),obs("r1","2026-10-28 12:00")]
        new=[obs("r2","2026-10-26 13:00"),obs("r2","2026-10-28 13:00")]
        coverage={(run,"due_to_arrive","2026-10-26"):True for run in ("r1","r2")}
        revisions,_,_,_=match_runs(old,new,coverage)
        self.assertEqual(len(revisions),2)

    def test_ambiguous_candidates_never_become_revision(self):
        coverage={(run,"due_to_arrive","2026-10-26"):True for run in ("r1","r2")}
        revisions, ambiguous, _, _=match_runs([obs("r1","2026-10-26 12:00")],[obs("r2","2026-10-26 11:00"),obs("r2","2026-10-26 13:00")],coverage)
        self.assertFalse(revisions); self.assertEqual(len(ambiguous),1)

    def test_continuity_bound_includes_three_hours(self):
        coverage = {(run,"due_to_arrive","2026-10-26"): True for run in ("r1", "r2")}
        revisions, _, _, _ = match_runs(
            [obs("r1", "2026-10-26 12:00")],
            [obs("r2", "2026-10-26 15:00")], coverage)
        self.assertEqual(len(revisions), 1)

    def test_continuity_bound_excludes_more_than_three_hours(self):
        coverage = {(run,"due_to_arrive","2026-10-26"): True for run in ("r1", "r2")}
        revisions, _, _, insufficient = match_runs(
            [obs("r1", "2026-10-26 12:00")],
            [obs("r2", "2026-10-26 15:01")], coverage)
        self.assertFalse(revisions)
        self.assertEqual(insufficient[0]["reason"], "no_defensible_successor_or_out_of_coverage")

    def test_timezone_incompatible_pair_is_insufficient_evidence(self):
        coverage = {(run,"due_to_arrive","2026-10-26"): True for run in ("r1", "r2")}
        revisions, _, _, insufficient = match_runs(
            [obs("r1", "2026-10-26 12:00")],
            [obs("r2", "2026-10-26T13:00:00+00:00")], coverage)
        self.assertFalse(revisions)
        self.assertEqual(insufficient[0]["reason"], "timezone_incompatible_source_time")


if __name__ == "__main__":
    unittest.main()
