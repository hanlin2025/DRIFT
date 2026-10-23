package com.drift.backend.shipment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Singleton durable gate for the one-time risk-alert activation boundary. */
@Entity
@Table(name = "shipment_risk_alert_activation")
public class ShipmentRiskAlertActivation {

	static final short SINGLETON_ID = 1;

	@Id
	private short id;

	@Column(nullable = false)
	private long generation;

	@Column(name = "activated_at")
	private Instant activatedAt;

	@Version
	@Column(nullable = false)
	private Long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ShipmentRiskAlertActivation() {
	}

	public boolean isActive() { return activatedAt != null; }
	void activate(Instant now) {
		if (isActive()) {
			return;
		}
		generation = 1;
		activatedAt = now;
		updatedAt = now;
	}
	public long getGeneration() { return generation; }
	public Instant getActivatedAt() { return activatedAt; }
}
