package com.drift.backend.shipment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Immutable event snapshot for one initial alertable evaluation or upward risk transition. */
@Entity
@Table(name = "shipment_risk_transitions", uniqueConstraints = @UniqueConstraint(
		name = "shipment_risk_transitions_shipment_sequence_key", columnNames = { "shipment_id", "sequence" }))
public class ShipmentRiskTransition {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shipment_id", nullable = false)
	private Shipment shipment;

	@Column(name = "sequence", nullable = false)
	private long sequence;

	@Column(name = "activation_generation", nullable = false)
	private long activationGeneration;

	@Enumerated(EnumType.STRING)
	@Column(name = "transition_type", nullable = false, length = 16)
	private ShipmentRiskTransitionType transitionType;

	@Enumerated(EnumType.STRING)
	@Column(name = "previous_risk_level", length = 16)
	private ShipmentRisk.Level previousRiskLevel;

	@Enumerated(EnumType.STRING)
	@Column(name = "risk_level", nullable = false, length = 16)
	private ShipmentRisk.Level riskLevel;

	@Column(name = "explanation", nullable = false)
	private String explanation;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "recipient_resolution_completed_at")
	private Instant recipientResolutionCompletedAt;

	protected ShipmentRiskTransition() {
	}

	public ShipmentRiskTransition(Shipment shipment, long sequence, ShipmentRiskTransitionType transitionType,
			ShipmentRisk.Level previousRiskLevel, ShipmentRisk risk, Instant occurredAt) {
		this(shipment, sequence, 0, transitionType, previousRiskLevel, risk, occurredAt);
	}

	public ShipmentRiskTransition(Shipment shipment, long sequence, long activationGeneration,
			ShipmentRiskTransitionType transitionType,
			ShipmentRisk.Level previousRiskLevel, ShipmentRisk risk, Instant occurredAt) {
		if (shipment == null || sequence <= 0 || transitionType == null || risk == null || risk.level() == null
				|| risk.explanation() == null || occurredAt == null) {
			throw new IllegalArgumentException("Transition identity, risk snapshot, and timestamp are required");
		}
		if ((transitionType == ShipmentRiskTransitionType.INITIAL) != (previousRiskLevel == null)) {
			throw new IllegalArgumentException("INITIAL transitions require a null previous level; RISE transitions require one");
		}
		if (activationGeneration < 0) {
			throw new IllegalArgumentException("Activation generation cannot be negative");
		}
		this.shipment = shipment;
		this.sequence = sequence;
		this.activationGeneration = activationGeneration;
		this.transitionType = transitionType;
		this.previousRiskLevel = previousRiskLevel;
		this.riskLevel = risk.level();
		this.explanation = risk.explanation();
		this.occurredAt = occurredAt;
		this.createdAt = occurredAt;
	}

	public Long getId() { return id; }
	public Shipment getShipment() { return shipment; }
	public long getSequence() { return sequence; }
	public long getActivationGeneration() { return activationGeneration; }
	public ShipmentRiskTransitionType getTransitionType() { return transitionType; }
	public ShipmentRisk.Level getPreviousRiskLevel() { return previousRiskLevel; }
	public ShipmentRisk.Level getRiskLevel() { return riskLevel; }
	public String getExplanation() { return explanation; }
	public Instant getOccurredAt() { return occurredAt; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getRecipientResolutionCompletedAt() { return recipientResolutionCompletedAt; }

	void markRecipientResolutionCompleted(Instant completedAt) {
		if (completedAt == null || recipientResolutionCompletedAt != null) {
			throw new IllegalStateException("Recipient resolution can only be completed once");
		}
		recipientResolutionCompletedAt = completedAt;
	}
}
