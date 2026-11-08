package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Keeps the late-arrival chance API tests on one instant so position age and the passed departure are exact. */
@TestConfiguration
class LateArrivalChanceClock {

	static final Instant NOW = Instant.parse("2026-10-26T04:00:00Z");

	@Bean
	Clock clock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}
}
