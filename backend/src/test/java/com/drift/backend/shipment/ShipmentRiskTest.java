package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ShipmentRisk.Level;

class ShipmentRiskTest {

	private final ConnectionWindowService windows = new ConnectionWindowService();

	@Test
	void ratesTheConnectionWindowAtTheTwelveAndTwentyFourHourBoundaries() {
		assertThat(risk("2026-10-15T08:00:00Z", "2026-10-15T19:59:59Z").level()).isEqualTo(Level.HIGH);
		assertThat(risk("2026-10-15T08:00:00Z", "2026-10-15T20:00:00Z").level()).isEqualTo(Level.MEDIUM);
		assertThat(risk("2026-10-15T08:00:00Z", "2026-10-16T07:59:59Z").level()).isEqualTo(Level.MEDIUM);
		assertThat(risk("2026-10-15T08:00:00Z", "2026-10-16T08:00:00Z").level()).isEqualTo(Level.LOW);
	}

	@Test
	void explainsEachLevel() {
		assertThat(risk("2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z").explanation())
				.isEqualTo("Less than 12 hours to transfer cargo between vessels.");
		assertThat(risk("2026-10-15T08:00:00Z", "2026-10-16T02:00:00Z").explanation())
				.isEqualTo("Less than 24 hours to transfer cargo between vessels.");
		assertThat(risk("2026-10-15T08:00:00Z", "2026-10-17T08:00:00Z").explanation())
				.isEqualTo("At least 24 hours to transfer cargo between vessels.");
	}

	@Test
	void marksAFeederDepartingBeforeTheMotherArrivesAsCritical() {
		ShipmentRisk risk = risk("2026-10-15T08:00:00Z", "2026-10-15T08:00:00Z");

		assertThat(risk.level()).isEqualTo(Level.CRITICAL);
		assertThat(risk.explanation())
				.isEqualTo("The feeder vessel is planned to depart before the mother vessel arrives.");
	}

	@Test
	void leavesTheRiskUnassessedUntilTheFeederIsScheduled() {
		assertThat(ShipmentRisk.of(windows.calculate(OffsetDateTime.parse("2026-10-15T08:00:00Z"), null))).isNull();
	}

	private ShipmentRisk risk(String motherArrival, String feederDeparture) {
		return ShipmentRisk.of(windows.calculate(OffsetDateTime.parse(motherArrival), OffsetDateTime.parse(feederDeparture)));
	}
}
