import unittest
import mpa_movement_pilot as p
class TestWatchlist(unittest.TestCase):
 def test_five_distinct_valid_queries(self):self.assertEqual(len(p.WATCH),5);self.assertEqual(len({x[2] for x in p.WATCH}),5);self.assertTrue(all(x[0].isdigit() and x[2] for x in p.WATCH))
if __name__=='__main__':unittest.main()
