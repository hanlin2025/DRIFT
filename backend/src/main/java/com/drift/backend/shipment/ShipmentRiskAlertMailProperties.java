package com.drift.backend.shipment;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit configuration for risk-alert mail; it is disabled unless an operator enables it. */
@ConfigurationProperties(prefix = "app.risk-alerts.mail")
public class ShipmentRiskAlertMailProperties {

	private boolean enabled;
	private String frontendBaseUrl = "";
	private String from = "";
	private Duration claimLease = Duration.ofMinutes(1);
	private boolean dispatchEnabled;
	private Duration pollInterval = Duration.ofMinutes(1);
	private int batchSize = 50;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getFrontendBaseUrl() {
		return frontendBaseUrl;
	}

	public void setFrontendBaseUrl(String frontendBaseUrl) {
		this.frontendBaseUrl = frontendBaseUrl;
	}

	public String getFrom() {
		return from;
	}

	public void setFrom(String from) {
		this.from = from;
	}

	public Duration getClaimLease() {
		return claimLease;
	}

	public void setClaimLease(Duration claimLease) {
		this.claimLease = claimLease;
	}

	public boolean isDispatchEnabled() {
		return dispatchEnabled;
	}

	public void setDispatchEnabled(boolean dispatchEnabled) {
		this.dispatchEnabled = dispatchEnabled;
	}

	public Duration getPollInterval() {
		return pollInterval;
	}

	public void setPollInterval(Duration pollInterval) {
		this.pollInterval = pollInterval;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	/** Returns a user-safe configuration problem instead of allowing an accidental SMTP call. */
	public String unavailableReason(boolean javaMailSenderAvailable) {
		if (!enabled) {
			return "Risk alert mail is disabled";
		}
		if (!javaMailSenderAvailable) {
			return "Risk alert mail sender is unavailable";
		}
		if (from == null || from.isBlank()) {
			return "Risk alert sender address is not configured";
		}
		if (!hasValidFrontendBaseUrl()) {
			return "Risk alert frontend base URL is not configured";
		}
		if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
			return "Risk alert claim lease is invalid";
		}
		return null;
	}

	boolean hasValidFrontendBaseUrl() {
		if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(frontendBaseUrl.trim());
			return uri.isAbsolute() && uri.getHost() != null
					&& ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
					&& uri.getQuery() == null && uri.getFragment() == null;
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}
}
