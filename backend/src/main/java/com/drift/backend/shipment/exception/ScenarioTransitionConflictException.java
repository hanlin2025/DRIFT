package com.drift.backend.shipment.exception;

public class ScenarioTransitionConflictException extends RuntimeException {
	private final String code;

	public ScenarioTransitionConflictException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() { return code; }
}
