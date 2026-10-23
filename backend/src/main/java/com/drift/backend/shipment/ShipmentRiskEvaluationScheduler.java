package com.drift.backend.shipment;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded keyset sweep. Each evaluator invocation supplies its own transaction and row locks. */
@Component
public class ShipmentRiskEvaluationScheduler {
	private static final Logger log = LoggerFactory.getLogger(ShipmentRiskEvaluationScheduler.class);
	private final ShipmentRepository shipments;
	private final ShipmentRiskEvaluator evaluator;
	private final ShipmentRiskAlertActivationStore activation;
	private final ShipmentRiskAlertEvaluationProperties properties;

	ShipmentRiskEvaluationScheduler(ShipmentRepository shipments, ShipmentRiskEvaluator evaluator,
			ShipmentRiskAlertActivationStore activation, ShipmentRiskAlertEvaluationProperties properties) {
		this.shipments = shipments; this.evaluator = evaluator; this.activation = activation; this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${app.risk-alerts.evaluation.interval:15m}")
	public void scheduledSweep() { sweep(); }

	public void sweep() {
		if (!properties.isEnabled() || !activation.lockForRiskInput().isActive()) return;
		Long afterId = 0L;
		while (true) {
			List<Long> ids = shipments.findActiveIdsAfter(afterId, PageRequest.of(0, properties.getBatchSize()));
			if (ids.isEmpty()) return;
			for (Long id : ids) {
				try { evaluator.evaluate(id); }
				catch (RuntimeException ex) { log.warn("Scheduled risk evaluation failed for shipment {}: {}", id, ex.getClass().getSimpleName()); }
				afterId = id;
			}
		}
	}
}
