package com.drift.backend.shipment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** A failed post-commit evaluation never changes the outcome of its already committed shipment write. */
@Component
class ShipmentRiskEvaluationDispatcher {
	private static final Logger log = LoggerFactory.getLogger(ShipmentRiskEvaluationDispatcher.class);
	private final ShipmentRiskEvaluator evaluator;

	ShipmentRiskEvaluationDispatcher(ShipmentRiskEvaluator evaluator) { this.evaluator = evaluator; }

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void evaluateAfterCommit(ShipmentRiskEvaluationRequestedEvent event) {
		try {
			evaluator.evaluate(event.shipmentId());
		} catch (RuntimeException ex) {
			log.warn("Risk evaluation after shipment commit failed for shipment {}: {}", event.shipmentId(),
					ex.getClass().getSimpleName());
		}
	}
}
