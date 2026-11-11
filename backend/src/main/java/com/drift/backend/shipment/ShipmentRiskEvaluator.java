package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.maritimechange.VerifiedMpaArrival;
import com.drift.backend.maritimechange.VerifiedMpaArrivalResolver;

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
	private final ShipmentRiskAlertActivationStore activation;
	private final VerifiedMpaArrivalResolver mpaArrivals;
	private final Clock clock;

	public ShipmentRiskEvaluator(ShipmentRepository shipments, ShipmentRiskStateStore states,
			ShipmentRiskTransitionRepository transitions, ShipmentConnectionRisk connectionRisk,
			ShipmentRiskAlertRecipientResolver recipients, ShipmentRiskAlertActivationStore activation,
			VerifiedMpaArrivalResolver mpaArrivals, ObjectProvider<Clock> clocks) {
		this.shipments = shipments;
		this.states = states;
		this.transitions = transitions;
		this.connectionRisk = connectionRisk;
		this.recipients = recipients;
		this.activation = activation;
		this.mpaArrivals = mpaArrivals;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	/**
	 * Atomically evaluates one shipment. The shipment and its risk-state row are locked before the
	 * previous valid baseline is compared and a transition sequence is allocated.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ShipmentRiskEvaluationOutcome evaluate(Long shipmentId) {
		return evaluateRisk(shipmentId, shipment -> mpaArrivals.latestFor(shipment)
				.map(evidence -> assessMpa(shipment, evidence))
				.orElseGet(() -> connectionRisk.assess(shipment).risk()));
	}

	/** Evaluates a source-backed MPA arrival while leaving the shipment's planned schedule unchanged. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ShipmentRiskEvaluationOutcome evaluateMpaExpectedArrival(Long shipmentId, VerifiedMpaArrival evidence) {
		if (evidence == null) throw new IllegalArgumentException("Verified MPA arrival is required");
		return evaluateRisk(shipmentId, shipment -> assessMpa(shipment, evidence));
	}

	private ShipmentRisk assessMpa(Shipment shipment, VerifiedMpaArrival evidence) {
		if (shipment.getPlannedFeederDepartureAt() == null
				|| !shipment.getPlannedFeederDepartureAt().toInstant().isAfter(Instant.now(clock))) return null;
		return withSource(connectionRisk.assessMpaExpectedArrival(shipment, evidence.expectedArrivalAt()), evidence);
	}

	private static ShipmentRisk withSource(ShipmentRisk risk, VerifiedMpaArrival evidence) {
		if (risk == null) return null;
		String explanation = "Based on a verified LIVE MPA expected arrival of " + evidence.expectedArrivalAt()
				+ " for source " + evidence.sourceIdentity() + " (source time " + evidence.sourceEventAt()
				+ ", retrieved " + evidence.retrievedAtUtc() + ", provenance " + evidence.provenance()
				+ "). Vessel IMO, port and prior scheduled arrival matched. This connection-window assessment does not establish delay causation. "
				+ risk.explanation();
		return new ShipmentRisk(risk.level(), explanation);
	}

	private ShipmentRiskEvaluationOutcome evaluateRisk(Long shipmentId, Function<Shipment, ShipmentRisk> riskAssessment) {
		if (shipmentId == null) {
			throw new IllegalArgumentException("Shipment id is required");
		}
		ShipmentRiskAlertActivation activeGeneration = activation.lockForRiskInput();

		Shipment shipment = shipments.findByIdForRiskEvaluation(shipmentId).orElse(null);
		if (shipment == null) {
			return ShipmentRiskEvaluationOutcome.NOT_FOUND;
		}
		if (shipment.getStatus() == ShipmentStatus.ARCHIVED) {
			return ShipmentRiskEvaluationOutcome.ARCHIVED;
		}

		ShipmentRiskState state = states.lockOrInitializeSilently(shipmentId);
		ShipmentRisk risk = riskAssessment.apply(shipment);
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
				persistTransition(shipment, state, activeGeneration.getGeneration(), ShipmentRiskTransitionType.INITIAL, null, risk, evaluatedAt);
				yield ShipmentRiskEvaluationOutcome.INITIAL_TRANSITION;
			}
			case RISE -> {
				persistTransition(shipment, state, activeGeneration.getGeneration(), ShipmentRiskTransitionType.RISE, previousRiskLevel, risk, evaluatedAt);
				yield ShipmentRiskEvaluationOutcome.RISE_TRANSITION;
			}
			case NONE -> ShipmentRiskEvaluationOutcome.NO_TRANSITION;
			case INCOMPLETE -> throw new IllegalStateException("Incomplete risk was handled before persistence");
		};
	}

	private void persistTransition(Shipment shipment, ShipmentRiskState state, long activationGeneration, ShipmentRiskTransitionType type,
			ShipmentRisk.Level previousRiskLevel, ShipmentRisk risk, Instant occurredAt) {
		ShipmentRiskTransition transition = transitions.saveAndFlush(new ShipmentRiskTransition(shipment,
				state.allocateTransitionSequence(), activationGeneration, type, previousRiskLevel, risk, occurredAt));
		recipients.resolveNewTransition(transition);
	}
}
