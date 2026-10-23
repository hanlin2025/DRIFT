package com.drift.backend.shipment;

public enum ShipmentRiskAlertDeliveryStatus {
	PENDING,
	SENT,
	RETRYABLE_FAILURE,
	EXHAUSTED,
	SUPPRESSED
}
