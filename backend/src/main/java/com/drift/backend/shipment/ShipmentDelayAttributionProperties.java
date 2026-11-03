package com.drift.backend.shipment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Thresholds above which an NEA reading near the port counts as adverse weather. */
@ConfigurationProperties(prefix = "app.delay-attribution")
public record ShipmentDelayAttributionProperties(
		double adverseRainfallMmPerHour,
		double adverseWindKnots) {

	static final double DEFAULT_RAINFALL = 10;
	static final double DEFAULT_WIND = 22;

	public ShipmentDelayAttributionProperties {
		adverseRainfallMmPerHour = adverseRainfallMmPerHour > 0 ? adverseRainfallMmPerHour : DEFAULT_RAINFALL;
		adverseWindKnots = adverseWindKnots > 0 ? adverseWindKnots : DEFAULT_WIND;
	}

	static ShipmentDelayAttributionProperties defaults() {
		return new ShipmentDelayAttributionProperties(DEFAULT_RAINFALL, DEFAULT_WIND);
	}
}
