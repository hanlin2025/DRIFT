package com.drift.backend.shipment.exception;

public class ControlledScenarioUnavailableException extends RuntimeException {
	private final String code;
	private final int status;

	public ControlledScenarioUnavailableException(String code, int status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public String getCode() { return code; }
	public int getStatus() { return status; }
}
