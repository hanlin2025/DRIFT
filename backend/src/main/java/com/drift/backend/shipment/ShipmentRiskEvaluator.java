package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
	 * Calculates and persists shipment-risk state, transitions, and their initial recipient snapshots.
 */
@Service
public class ShipmentRiskEvaluator {

	private final ShipmentRepository shipments;
	private final ShipmentRiskStateStore states;
	private final ShipmentRiskTransitionRepository transitions;
	private final ShipmentConnectionRisk connectionRisk;
	private final ShipmentRiskAlertRecipientResolver recipients;
	private final Clock clock;

	public ShipmentRiskEvaluator(ShipmentRepository shipments, ShipmentRiskStateStore states,
			ShipmentRiskTransitionRepository transitions, ShipmentConnectionRisk connectionRisk,
			ShipmentRiskAlertRecipientResolver recipients, ObjectProvider<Clock> clocks) {
		this.shipments = shipments;
		this.states = states;
		this.transitions = transitions;
		this.connectionRisk = connectionRisk;
		this.recipients = recipients;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	/**
	 * Atomically evaluates one shipment. The shipment and its risk-state row are locked before the
	 * previous valid baseline is compared and a transition sequence is allocated.
	 */
	@Transactional
	public ShipmentRiskEvaluationOutcome evaluate(Long shipmentId) {
		if (shipmentId == null) {
			throw new IllegalArgumentException("Shipment id is required");
		}

		Shipment shipment = shipments.findByIdForRiskEvaluation(shipmentId).orElse(null);
		if (shipment == null) {
			return ShipmentRiskEvaluationOutcome.NOT_FOUND;
		}
		if (shipment.getStatus() == ShipmentStatus.ARCHIVED) {
			return ShipmentRiskEvaluationOutcome.ARCHIVED;
		}

		ShipmentRiskState state = states.lockOrInitializeSilently(shipmentId);
		ShipmentRisk risk = connectionRisk.assess(shipment).risk();
		ShipmentRiskTransitionDecision.Type decision = ShipmentRiskTransitionDecision.decide(
				state.getBaselineMode(), state.getLastRiskLevel(), risk);
		if (decision == ShipmentRiskTransitionDecision.Type.INCOMPLETE) {
			return ShipmentRiskEvaluationOutcome.INCOMPLETE;
		}

		Instant evaluatedAt = Instant.now(clock);
		ShipmentRisk.Level previousRiskLevel = state.getLastRiskLevel();
		state.recordValidEvaluation(risk, evaluatedAt);
		return switch (decision) {
			case BASELINE -> ShipmentRiskEvaluationOutcome.BASELINED;
			case INITIAL -> {
				persistTransition(shipment, state, ShipmentRiskTransitionType.INITIAL, null, risk, evaluatedAt);
				yield ShipmentRiskEvaluationOutcome.INITIAL_TRANSITION;
			}
			case RISE -> {
				persistTransition(shipment, state, ShipmentRiskTransitionType.RISE, previousRiskLevel, risk, evaluatedAt);
				yield ShipmentRiskEvaluationOutcome.RISE_TRANSITION;
			}
			case NONE -> ShipmentRiskEvaluationOutcome.NO_TRANSITION;
			case INCOMPLETE -> throw new IllegalStateException("Incomplete risk was handled before persistence");
		};
	}

	private void persistTransition(Shipment shipment, ShipmentRiskState state, ShipmentRiskTransitionType type,
			ShipmentRisk.Level previousRiskLevel, ShipmentRisk risk, Instant occurredAt) {
		ShipmentRiskTransition transition = transitions.saveAndFlush(new ShipmentRiskTransition(shipment,
				state.allocateTransitionSequence(), type, previousRiskLevel, risk, occurredAt));
		recipients.resolveNewTransition(transition);
	}
}
