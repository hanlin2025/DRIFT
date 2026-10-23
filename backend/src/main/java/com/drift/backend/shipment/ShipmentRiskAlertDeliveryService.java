package com.drift.backend.shipment;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Sends one explicitly selected PENDING delivery; CDG-197 will dispatch retryable deliveries. */
@Service
public class ShipmentRiskAlertDeliveryService {

	private final ShipmentRiskAlertDeliveryStore store;
	private final ObjectProvider<JavaMailSender> mailSenders;

	public ShipmentRiskAlertDeliveryService(ShipmentRiskAlertDeliveryStore store,
			ObjectProvider<JavaMailSender> mailSenders) {
		this.store = store;
		this.mailSenders = mailSenders;
	}

	/**
	 * Submits one pending delivery to SMTP. A successful result means the configured SMTP client
	 * accepted the message, not that it reached a recipient inbox.
	 */
	public ShipmentRiskAlertDeliveryResult sendDelivery(Long deliveryId) {
		JavaMailSender sender = mailSenders.getIfAvailable();
		ShipmentRiskAlertDeliveryStore.ClaimResult claim = store.claimInitialDelivery(deliveryId, sender != null);
		if (claim.kind() == ShipmentRiskAlertDeliveryStore.ClaimResult.Kind.SUPPRESSED) {
			return ShipmentRiskAlertDeliveryResult.SUPPRESSED;
		}
		if (claim.kind() != ShipmentRiskAlertDeliveryStore.ClaimResult.Kind.CLAIMED) {
			return ShipmentRiskAlertDeliveryResult.NOT_PENDING;
		}

		try {
			sender.send(claim.message());
			return store.markSent(deliveryId, claim.token())
					? ShipmentRiskAlertDeliveryResult.SENT : ShipmentRiskAlertDeliveryResult.CLAIM_LOST;
		} catch (RuntimeException ex) {
			return store.markInitialFailure(deliveryId, claim.token(), sanitizedFailure(ex))
					? ShipmentRiskAlertDeliveryResult.RETRYABLE_FAILURE : ShipmentRiskAlertDeliveryResult.CLAIM_LOST;
		}
	}

	private static String sanitizedFailure(RuntimeException ex) {
		String type = ex.getClass().getSimpleName();
		return "SMTP submission failed" + (type.isBlank() ? "" : " (" + type + ")");
	}
}
