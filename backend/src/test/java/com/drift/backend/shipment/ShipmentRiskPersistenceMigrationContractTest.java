package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ShipmentRiskPersistenceMigrationContractTest {

	@Test
	void migrationBackfillsOnlySilentStatesAndCreatesNoHistoricalEventsOrDeliveries() throws IOException {
		String migration;
		try (var stream = getClass().getClassLoader()
				.getResourceAsStream("db/migration/V23__create_shipment_risk_alert_persistence.sql")) {
			migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration).contains("INSERT INTO shipment_risk_states")
				.contains("SELECT id, 'SILENT_FIRST_VALID', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP")
				.contains("FROM shipments")
				.contains("last_risk_level VARCHAR(16)")
				.contains("UNIQUE (shipment_id, sequence)")
				.contains("UNIQUE (transition_id, user_id)")
				.doesNotContain("INSERT INTO shipment_risk_transitions")
				.doesNotContain("INSERT INTO shipment_risk_alert_deliveries")
				.doesNotContain("ShipmentRisk");
	}
}
