package com.drift.backend.shipment;

/** Safe, recipient-free delivery state for the current in-memory scenario epoch. */
public enum ControlledScenarioEmailStatus {
	NOT_TRIGGERED,
	SUBMISSION_PENDING,
	SUBMITTING,
	SUBMITTED,
	FAILED,
	PARTIAL_FAILURE,
	DISABLED,
	NO_APPROVED_RECIPIENTS
}
