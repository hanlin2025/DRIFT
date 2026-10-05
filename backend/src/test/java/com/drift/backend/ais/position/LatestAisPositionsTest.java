package com.drift.backend.ais.position;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class LatestAisPositionsTest {

	private final LatestAisPositions positions = new LatestAisPositions();

	@Test
	void keepsTheNewerReportForTheSameVessel() {
		positions.record(position("368207620", "Ever Steady", Instant.parse("2026-10-05T01:00:00Z")));
		positions.record(position("368207620", "EVER   STEADY", Instant.parse("2026-10-05T02:00:00Z")));
		positions.record(position("368207620", "Ever Steady", Instant.parse("2026-10-05T00:30:00Z")));

		AisPosition latest = positions.findByVesselName("ever steady").orElseThrow();
		assertThat(latest.ingestedAt()).isEqualTo(Instant.parse("2026-10-05T02:00:00Z"));
		assertThat(positions.findByMmsi("368207620").orElseThrow()).isEqualTo(latest);

		positions.clear();
		assertThat(positions.findByMmsi("368207620")).isEmpty();
		assertThat(positions.findByVesselName("ever steady")).isEmpty();
	}

	private static AisPosition position(String mmsi, String name, Instant ingestedAt) {
		return new AisPosition(mmsi, name, new BigDecimal("1.3"), new BigDecimal("103.8"),
				null, null, null, null, null, null, ingestedAt);
	}
}
