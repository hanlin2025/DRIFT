package com.drift.backend.shipment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Persisted last valid risk evaluation, independent from alert delivery outcomes. */
@Entity
@Table(name = "shipment_risk_states")
public class ShipmentRiskState {

	@Id
	private Long shipmentId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shipment_id", nullable = false)
	private Shipment shipment;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_risk_level", length = 16)
	private ShipmentRisk.Level lastRiskLevel;

	@Column(name = "last_explanation")
	private String lastExplanation;

	@Column(name = "evaluated_at")
	private Instant evaluatedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "baseline_mode", nullable = false, length = 32)
	private ShipmentRiskBaselineMode baselineMode;

	@Column(name = "last_transition_sequence", nullable = false)
	private long lastTransitionSequence;

	@Version
	@Column(nullable = false)
	private Long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ShipmentRiskState() {
	}

	public ShipmentRiskState(Shipment shipment, ShipmentRiskBaselineMode baselineMode, Instant now) {
		if (shipment == null || baselineMode == null || now == null) {
			throw new IllegalArgumentException("Shipment, baseline mode, and timestamp are required");
		}
		this.shipment = shipment;
		this.baselineMode = baselineMode;
		this.lastTransitionSequence = 0;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** Applies only valid evaluations; null risk preserves the last valid snapshot unchanged. */
	public boolean recordValidEvaluation(ShipmentRisk risk, Instant evaluatedAt) {
		if (risk == null) {
			return false;
		}
		if (evaluatedAt == null || risk.level() == null || risk.explanation() == null) {
			throw new IllegalArgumentException("A valid risk and evaluation timestamp are required");
		}
		this.lastRiskLevel = risk.level();
		this.lastExplanation = risk.explanation();
		this.evaluatedAt = evaluatedAt;
		this.updatedAt = evaluatedAt;
		return true;
	}

	/** Must be called while this state row is locked in the transaction creating its transition. */
	public long allocateTransitionSequence() {
		return ++lastTransitionSequence;
	}

	public Long getShipmentId() { return shipmentId; }
	public Shipment getShipment() { return shipment; }
	public ShipmentRisk.Level getLastRiskLevel() { return lastRiskLevel; }
	public String getLastExplanation() { return lastExplanation; }
	public Instant getEvaluatedAt() { return evaluatedAt; }
	public ShipmentRiskBaselineMode getBaselineMode() { return baselineMode; }
	public long getLastTransitionSequence() { return lastTransitionSequence; }
	public Long getVersion() { return version; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
