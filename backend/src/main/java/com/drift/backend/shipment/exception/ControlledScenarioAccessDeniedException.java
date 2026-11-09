package com.drift.backend.shipment.exception;

public class ControlledScenarioAccessDeniedException extends RuntimeException {
	public static final String CODE = "SCENARIO_ACCESS_DENIED";
	public ControlledScenarioAccessDeniedException() { super("Controlled scenario access is not authorized."); }
}
