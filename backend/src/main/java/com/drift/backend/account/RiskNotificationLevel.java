package com.drift.backend.account;

/**
 * Same names and severity order as {@code ShipmentRisk.Level}:
 * LOW, then MEDIUM, then HIGH, then CRITICAL.
 * A stored minimum includes its own level and every more severe level.
 * HIGH therefore means only HIGH and CRITICAL, which is the profile story's example.
 * Storing this value does not send email.
 */
public enum RiskNotificationLevel {
	LOW,
	MEDIUM,
	HIGH,
	CRITICAL;

	public boolean includes(RiskNotificationLevel actual) {
		return actual != null && actual.ordinal() >= ordinal();
	}
}
