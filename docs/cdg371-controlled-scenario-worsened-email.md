# CDG-371 controlled-scenario WORSENED email

This feature sends a synthetic demonstration email only when an authorized controlled-scenario session successfully advances from CHANGE to WORSENED. It is independent of shipment risk-alert evaluation, recipient resolution, delivery records, and activation generations. It does not update persistent shipment risk state or create real shipment risk transitions.

The email uses the existing Spring `JavaMailSender` and shared `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, and `MAIL_FROM_ADDRESS` configuration. It sends one individual message per configured recipient, never to a request-provided address. Each link opens the corresponding configured shipment detail page, where assessment generation remains a user action.

## Render configuration

Keep the new email-specific variables unset/defaulted unless an isolated controlled demonstration has been approved:

```text
CONTROLLED_SCENARIO_EMAIL_ENABLED=false
CONTROLLED_SCENARIO_EMAIL_RECIPIENTS=
```

For an explicitly approved demonstration, configure:

```text
CONTROLLED_SCENARIO_EMAIL_ENABLED=true
CONTROLLED_SCENARIO_EMAIL_RECIPIENTS=demo.one@example.test,demo.two@example.test
```

The recipient variable is a comma-separated, validated, case-insensitively deduplicated allowlist. Invalid entries are discarded. If none remain, delivery is skipped and the session reports `NO_APPROVED_RECIPIENTS`. The feature also requires the pre-existing controlled-scenario configuration: `CONTROLLED_SCENARIO_ENABLED=true`, the designated demo company and authorized user IDs, and the two configured demo shipment IDs. Existing SMTP variables must already be configured; this change adds no provider configuration. No Render values were changed as part of this implementation.

## Transition, reset, and delivery semantics

The backend creates the notification event only from the committed NEXT command that moves the session to WORSENED. BASELINE, CHANGE, GET, repeated reads, AI assessment requests, duplicate idempotency-key replays, and invalid/unauthorized commands do not enqueue mail. Reset creates a new epoch and permits one notification for a later WORSENED transition in that epoch. The existing process-local session lifetime and command limits bound this behavior.

SMTP submission runs after transaction commit and after the scenario session monitor is released. A failed submission leaves the scenario WORSENED and reports `FAILED`; partial allowlist delivery reports `PARTIAL_FAILURE`. Failures are logged with session/revision, recipient ordinal, and exception class only. There is no automatic retry because SMTP can accept a message before the caller observes a timeout, so a retry could duplicate it. This is at-most-one submission attempt per recipient per in-memory session epoch, not an exactly-once SMTP guarantee. The session and its delivery status are process-local; a restart loses them, consistent with the controlled scenario's existing in-memory lifecycle.

The WORSENED snapshot currently calculates risk from the controlled fixture's scheduled vessel observations and shipment schedule/deadline fields. This email feature does not add live MPA/NEA evidence ingestion or imply that external advisories change the calculated level.

## Safe verification

Automated tests use a mocked `JavaMailSender`. They do not send external mail, enable normal risk-alert delivery, call an AI provider, or change deployment configuration. The scenario integration test continues to require its dedicated `DRIFT_CDG279_*` test database guard.
