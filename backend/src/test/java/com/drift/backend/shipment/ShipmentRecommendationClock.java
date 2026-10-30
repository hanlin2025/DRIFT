package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Keeps the recommendation API tests on one instant so a fresh position stays usable. */
@TestConfiguration
class ShipmentRecommendationClock {

	static final Instant NOW = Instant.parse("2026-10-26T04:00:00Z");

	@Bean
	Clock clock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}
}
