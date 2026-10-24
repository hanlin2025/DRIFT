package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Guards V25's durable activation boundary without mutating a shared database. */
class ShipmentRiskAlertActivationMigrationContractTest {

	@Test
	void createsTheInactiveGenerationZeroSingletonAndBackfillsExistingRowsAsGenerationZero() throws IOException {
		String migration;
		try (var stream = getClass().getClassLoader()
				.getResourceAsStream("db/migration/V25__add_shipment_risk_alert_activation.sql")) {
			migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(migration)
				.contains("CREATE TABLE shipment_risk_alert_activation")
				.contains("generation BIGINT NOT NULL DEFAULT 0")
				.contains("activated_at TIMESTAMPTZ")
				.contains("VALUES (1, 0, NULL")
				.contains("ADD COLUMN activation_generation BIGINT NOT NULL DEFAULT 0")
				.contains("shipment_risk_states")
				.contains("shipment_risk_transitions");
	}
}
