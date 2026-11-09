package com.drift.backend.shipment.exception;

public class ScenarioSessionNotFoundException extends RuntimeException {
	public static final String MESSAGE = "Scenario session not found";
	public static final String CODE = "SCENARIO_NOT_FOUND";

	public ScenarioSessionNotFoundException() { super(MESSAGE); }
}
