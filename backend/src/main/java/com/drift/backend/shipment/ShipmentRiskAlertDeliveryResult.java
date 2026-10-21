package com.drift.backend.shipment;

/** Observable result of one explicit initial delivery attempt. */
public enum ShipmentRiskAlertDeliveryResult {
	SENT,
	RETRYABLE_FAILURE,
	SUPPRESSED,
	NOT_PENDING,
	CLAIM_LOST
}
