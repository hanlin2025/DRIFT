package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Keeps the contribution API tests on one instant, including a position that is exactly six hours old. */
@TestConfiguration
class ShipmentRiskContributionClock {

	static final Instant NOW = Instant.parse("2026-10-26T04:00:00Z");

	@Bean
	Clock clock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}
}
