package com.drift.backend.shipment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.risk-alerts.evaluation")
public class ShipmentRiskAlertEvaluationProperties {
	private boolean enabled;
	private Duration interval = Duration.ofMinutes(15);
	private int batchSize = 100;
	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public Duration getInterval() { return interval; }
	public void setInterval(Duration interval) { this.interval = interval; }
	public int getBatchSize() { return batchSize; }
	public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
