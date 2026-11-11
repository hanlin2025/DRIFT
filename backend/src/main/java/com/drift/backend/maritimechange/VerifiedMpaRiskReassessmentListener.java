package com.drift.backend.maritimechange;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.drift.backend.shipment.ShipmentRiskAlertActivationStore;
import com.drift.backend.shipment.ShipmentRiskAlertEvaluationProperties;
import com.drift.backend.shipment.ShipmentRiskEvaluator;

/** Sends only a verified, forward LIVE MPA mother-arrival revision through the existing risk pipeline. */
@Component
class VerifiedMpaRiskReassessmentListener {
	private static final Logger log = LoggerFactory.getLogger(VerifiedMpaRiskReassessmentListener.class);

	private final MaritimeSnapshotShipmentMatcher matcher;
	private final ShipmentRiskEvaluator evaluator;
	private final VerifiedMpaArrivalResolver arrivals;
	private final ShipmentRiskAlertEvaluationProperties evaluationProperties;
	private final ShipmentRiskAlertActivationStore activation;

	VerifiedMpaRiskReassessmentListener(MaritimeSnapshotShipmentMatcher matcher,
			ShipmentRiskEvaluator evaluator, VerifiedMpaArrivalResolver arrivals,
			ShipmentRiskAlertEvaluationProperties evaluationProperties, ShipmentRiskAlertActivationStore activation) {
		this.matcher = matcher;
		this.evaluator = evaluator;
		this.arrivals = arrivals;
		this.evaluationProperties = evaluationProperties;
		this.activation = activation;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void reassess(MaritimeSnapshotChangeDetectedEvent event) {
		MaritimeSnapshotChange change = event == null ? null : event.change();
		if (change == null || !evaluationProperties.isEnabled()
				|| !activation.lockForRiskInput().isActive()) {
			return;
		}

		MaritimeShipmentMatch matches = matcher.matchActive(change);
		for (MaritimeShipmentCandidate candidate : matches.candidates()) {
			VerifiedMpaArrival evidence = arrivals.verifiedFor(change, candidate).orElse(null);
			if (evidence == null) continue;
			try {
				evaluator.evaluateMpaExpectedArrival(candidate.shipmentId(), evidence);
			} catch (RuntimeException ex) {
				log.warn("Verified MPA risk reassessment failed shipment={} exception={}", candidate.shipmentId(),
						ex.getClass().getSimpleName());
			}
		}
	}

}
