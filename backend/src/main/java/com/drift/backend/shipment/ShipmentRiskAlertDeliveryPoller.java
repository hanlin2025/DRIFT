package com.drift.backend.shipment;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded poller; the per-delivery service owns claims, SMTP, and outcome finalization. */
@Component
public class ShipmentRiskAlertDeliveryPoller {

	private static final Logger log = LoggerFactory.getLogger(ShipmentRiskAlertDeliveryPoller.class);

	private final ShipmentRiskAlertDeliveryDueStore dueDeliveries;
	private final ShipmentRiskAlertDeliveryService deliveryService;
	private final ShipmentRiskAlertMailProperties mail;

	public ShipmentRiskAlertDeliveryPoller(ShipmentRiskAlertDeliveryDueStore dueDeliveries,
			ShipmentRiskAlertDeliveryService deliveryService, ShipmentRiskAlertMailProperties mail) {
		this.dueDeliveries = dueDeliveries;
		this.deliveryService = deliveryService;
		this.mail = mail;
	}

	@Scheduled(fixedDelayString = "${app.risk-alerts.mail.poll-interval:1m}")
	public void pollDueDeliveries() {
		dispatchDueDeliveries();
	}

	/** Exposed for deterministic integration tests; production scheduling remains configuration-gated. */
	public void dispatchDueDeliveries() {
		List<Long> deliveryIds = dueDeliveries.lockDueDeliveryIds(mail.getBatchSize());
		for (Long deliveryId : deliveryIds) {
			try {
				deliveryService.sendDelivery(deliveryId);
			} catch (RuntimeException ex) {
				log.warn("Risk alert delivery {} could not be processed: {}", deliveryId, ex.getClass().getSimpleName());
			}
		}
	}
}
