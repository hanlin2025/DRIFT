package com.drift.backend.shipment;

/** Result of evaluating one shipment risk without performing recipient or delivery work. */
public enum ShipmentRiskEvaluationOutcome {
	NOT_FOUND,
	ARCHIVED,
	INCOMPLETE,
	BASELINED,
	NO_TRANSITION,
	INITIAL_TRANSITION,
	RISE_TRANSITION
}
