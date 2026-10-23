package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit one-time operation. Its write lock blocks every risk input while canonical baselines are recorded. */
@Service
public class ShipmentRiskAlertActivationService {
	private static final int BASELINE_BATCH_SIZE = 100;
	private static final String HISTORICAL_DELIVERY_REASON = "Suppressed during risk-alert activation";
	private final ShipmentRiskAlertActivationStore activation;
	private final ShipmentRepository shipments;
	private final ShipmentRiskStateStore states;
	private final ShipmentConnectionRisk connectionRisk;
	private final ShipmentRiskAlertDeliveryRepository deliveries;
	private final Clock clock;

	ShipmentRiskAlertActivationService(ShipmentRiskAlertActivationStore activation, ShipmentRepository shipments,
			ShipmentRiskStateStore states, ShipmentConnectionRisk connectionRisk,
			ShipmentRiskAlertDeliveryRepository deliveries, ObjectProvider<Clock> clocks) {
		this.activation = activation; this.shipments = shipments; this.states = states;
		this.connectionRisk = connectionRisk; this.deliveries = deliveries;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Transactional
	public ShipmentRiskAlertActivationResult activate() {
		ShipmentRiskAlertActivation gate = activation.lockForActivation();
		if (gate.isActive()) return ShipmentRiskAlertActivationResult.ALREADY_ACTIVE;
		long generation = 1;
		Instant now = Instant.now(clock);
		if (deliveries.countLiveClaimsForOlderGenerations(generation, now) > 0) {
			throw new IllegalStateException("Risk-alert activation requires all historical delivery claims to be quiesced");
		}
		Long afterId = 0L;
		while (true) {
			List<Long> ids = shipments.findActiveIdsAfter(afterId, PageRequest.of(0, BASELINE_BATCH_SIZE));
			if (ids.isEmpty()) break;
			for (Long id : ids) {
				Shipment shipment = shipments.findByIdForRiskEvaluation(id).orElse(null);
				if (shipment != null && shipment.getStatus() == ShipmentStatus.ACTIVE) {
					states.lockOrInitializeSilently(id).recordActivationBaseline(connectionRisk.assess(shipment).risk(), generation, now);
				}
				afterId = id;
			}
		}
		deliveries.suppressOlderGenerations(generation, HISTORICAL_DELIVERY_REASON, now);
		gate.activate(now);
		return ShipmentRiskAlertActivationResult.ACTIVATED;
	}
}
