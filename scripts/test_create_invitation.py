"""Unit coverage for local invitation company validation."""

import importlib.util
import unittest
from pathlib import Path
from unittest import mock

SPEC = importlib.util.spec_from_file_location(
    "create_invitation",
    Path(__file__).with_name("create-invitation.py"),
)
create_invitation = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(create_invitation)


class CreateInvitationCompanyTest(unittest.TestCase):
    def test_accepts_any_active_company_code(self):
        with mock.patch.object(create_invitation, "psql", return_value="PACIFIC_GATE") as psql:
            self.assertEqual(create_invitation.require_active_company("drift", "pacific_gate"), "PACIFIC_GATE")
        psql.assert_called_once()
        self.assertIn("UPPER(code)", psql.call_args.args[1])
        self.assertEqual(psql.call_args.args[2], {"company": "PACIFIC_GATE"})

    def test_rejects_missing_or_inactive_company(self):
        with mock.patch.object(create_invitation, "psql", return_value=""):
            with self.assertRaises(SystemExit) as raised:
                create_invitation.require_active_company("drift", "MISSING_CO")
        self.assertEqual(str(raised.exception), "No active organisation with code MISSING_CO")


if __name__ == "__main__":
    unittest.main()
