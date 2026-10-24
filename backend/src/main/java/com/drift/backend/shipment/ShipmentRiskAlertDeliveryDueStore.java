package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Selects a bounded due batch under PostgreSQL row locks, then releases those locks immediately. */
@Service
class ShipmentRiskAlertDeliveryDueStore {

	private final ShipmentRiskAlertDeliveryRepository deliveries;
	private final Clock clock;

	ShipmentRiskAlertDeliveryDueStore(ShipmentRiskAlertDeliveryRepository deliveries, ObjectProvider<Clock> clocks) {
		this.deliveries = deliveries;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Transactional
	List<Long> lockDueDeliveryIds(int batchSize) {
		if (batchSize <= 0) {
			throw new IllegalArgumentException("Risk alert batch size must be positive");
		}
		return deliveries.lockDueForClaim(Instant.now(clock), batchSize).stream()
				.map(ShipmentRiskAlertDelivery::getId).toList();
	}
}
