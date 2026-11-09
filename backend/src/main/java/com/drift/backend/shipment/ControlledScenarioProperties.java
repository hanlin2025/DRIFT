package com.drift.backend.shipment;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit operator-owned access and bounds for the non-production controlled scenario. */
@ConfigurationProperties(prefix = "app.controlled-scenario")
public class ControlledScenarioProperties {
	private boolean enabled;
	private Long demoCompanyId;
	private Set<Long> allowedUserIds = new LinkedHashSet<>();
	private Long shipmentAId;
	private Long shipmentBId;
	private Duration sessionTtl = Duration.ofHours(4);
	private int maxSessions = 100;
	private int maxCommandsPerSession = 64;

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public Long getDemoCompanyId() { return demoCompanyId; }
	public void setDemoCompanyId(Long demoCompanyId) { this.demoCompanyId = demoCompanyId; }
	public Set<Long> getAllowedUserIds() { return Set.copyOf(allowedUserIds); }
	public void setAllowedUserIds(Set<Long> allowedUserIds) {
		this.allowedUserIds = allowedUserIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedUserIds);
	}
	public Long getShipmentAId() { return shipmentAId; }
	public void setShipmentAId(Long shipmentAId) { this.shipmentAId = shipmentAId; }
	public Long getShipmentBId() { return shipmentBId; }
	public void setShipmentBId(Long shipmentBId) { this.shipmentBId = shipmentBId; }
	public Duration getSessionTtl() { return sessionTtl; }
	public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }
	public int getMaxSessions() { return maxSessions; }
	public void setMaxSessions(int maxSessions) { this.maxSessions = maxSessions; }
	public int getMaxCommandsPerSession() { return maxCommandsPerSession; }
	public void setMaxCommandsPerSession(int maxCommandsPerSession) { this.maxCommandsPerSession = maxCommandsPerSession; }

	public boolean isConfigured() {
		return demoCompanyId != null && demoCompanyId > 0 && allowedUserIds != null && !allowedUserIds.isEmpty()
				&& shipmentAId != null && shipmentAId > 0 && shipmentBId != null && shipmentBId > 0
				&& !shipmentAId.equals(shipmentBId) && sessionTtl != null && !sessionTtl.isNegative()
				&& !sessionTtl.isZero() && maxSessions > 0 && maxCommandsPerSession > 0;
	}
}
