package com.drift.backend.shipment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.risk-alerts.evaluation")
public class ShipmentRiskAlertEvaluationProperties {
	private static final Duration MIN_INTERVAL = Duration.ofMinutes(5);
	private static final Duration MAX_INTERVAL = Duration.ofMinutes(15);
	private boolean enabled;
	private Duration interval = Duration.ofMinutes(15);
	private int batchSize = 100;
	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public Duration getInterval() { return interval; }
	public void setInterval(Duration interval) {
		if (interval == null || interval.compareTo(MIN_INTERVAL) < 0 || interval.compareTo(MAX_INTERVAL) > 0) {
			throw new IllegalArgumentException("Risk evaluation interval must be between 5m and 15m");
		}
		this.interval = interval;
	}
	public int getBatchSize() { return batchSize; }
	public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
