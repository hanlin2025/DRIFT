package com.drift.backend.shipment;

import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Gives the synthetic AI fixture's synthetic port deterministic Singapore-area coordinates only in tests. */
@TestConfiguration
public class SyntheticPortCatalogTestConfiguration {
	@Bean
	@Primary
	PortCatalog syntheticPortCatalog() {
		return PortCatalog.of(Map.of("synthetic multi-source port", new PortCatalog.Coordinates(103.82, 1.26)));
	}
}
