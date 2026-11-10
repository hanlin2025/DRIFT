# Continuous shipment risk monitoring

DRIFT's scheduled evaluator runs a bounded keyset sweep of active shipments. For each shipment it calculates the existing connection risk, compares it with the last valid risk level persisted in `shipment_risk_states`, and persists only an initial alertable risk or an upward transition. Repeated or lower assessments update the baseline without creating another transition. A decrease therefore becomes the baseline for a later upward crossing.

The interval is configurable with `RISK_ALERTS_EVALUATION_INTERVAL`; supported values are 5 to 15 minutes, and the default is 15 minutes. `RISK_ALERTS_EVALUATION_ENABLED` gates the sweep. The scheduler uses separate transactions per shipment, and a failure for one shipment does not stop the remaining batch.

## Risk inputs and evidence

Reevaluation does not fetch external evidence. It reads the shipment's persisted planned mother arrival and feeder departure, plus the latest already persisted AIS position when one matches the mother vessel. The current MPA and NEA collection/change-event path is not connected to the risk evaluator, so MPA/NEA changes do not change risk. AI assessment remains a user action from the shipment detail page linked in the email.

## Activation and delivery controls

Risk evaluation stays gated until the durable risk-alert activation has completed. For a maintenance run against the intended database, set `RISK_ALERTS_ACTIVATION_COMMAND=true`, keep `RISK_ALERTS_EVALUATION_ENABLED=false` and both risk-mail flags false, and start the application once. The activation command silently baselines currently active shipments, suppresses deliveries from older generations, records the new generation, and exits the process. Return `RISK_ALERTS_ACTIVATION_COMMAND=false` for normal service starts.

After that baseline, set `RISK_ALERTS_EVALUATION_ENABLED=true` and choose an interval from `5m` through `15m`. This evaluates and records transitions; it does not itself enable the delivery poller. Keep `RISK_ALERTS_MAIL_ENABLED=false` and `RISK_ALERTS_DISPATCH_ENABLED=false` until delivery is explicitly approved and recipient preferences, frontend URL, SMTP configuration, and deployment behavior are reviewed. The poller is created only when dispatch is enabled. Eligible users still need the existing notification preference enabled with a minimum level; the selected level includes itself and all higher levels.

An email links to the recipient's authorized shipment detail route (`/logistics-manager/shipments/{id}` or `/importer/shipments/{id}`). AI assessment generation remains available as an explicit action on that page and is never invoked by the scheduler or email path.

## Offline A/B verification

`ShipmentRiskMonitoringScenarioIntegrationTests` uses a dedicated, empty PostgreSQL database guarded before Flyway starts. It creates one test user whose preference is HIGH and a shipment with unique synthetic vessel names. The evaluator uses only the controlled planned schedule values in this fixture; the names cannot match operational AIS vessels. The existing controlled-scenario JSON snapshots were not adapted as shipment records because they belong to a separate read-only AI replay and are not connected to persisted alert state.

The demonstration activates a silent LOW baseline, changes the synthetic connection window to MEDIUM, then HIGH, and repeats the unchanged HIGH sweep. The expected result is two risk transitions (LOW→MEDIUM and MEDIUM→HIGH), no eligible delivery at MEDIUM, exactly one eligible HIGH delivery, no duplicate after the repeated sweep, and exactly one call to a mocked mail sender. The test verifies the role-specific shipment detail link and never sends SMTP mail or calls an AI provider.

Provide these test-only environment variables for the focused integration test:

* `DRIFT_CDG198_MONITORING_E2E_JDBC_URL` must be a newly created database URL matching `jdbc:postgresql://127.0.0.1:5432/drift_cdg198_monitoring_e2e_<14 digits>_<12 lowercase hex digits>`.
* `DRIFT_CDG198_MONITORING_E2E_DB_USERNAME` and `DRIFT_CDG198_MONITORING_E2E_DB_PASSWORD` must be credentials for that database and its Flyway owner.
* `DRIFT_CDG198_MONITORING_E2E_OWNERSHIP_TOKEN` must match the database comment set by its creator. The guard refuses a database that is not empty and explicitly owned.

Run the focused checks from `backend/` after provisioning that database:

```sh
./mvnw -Dtest=ShipmentRiskEvaluationSchedulerTest test
./mvnw -Dtest=ShipmentRiskMonitoringScenarioIntegrationTests test
```

No public deployment or mass notification delivery is part of the verification.
