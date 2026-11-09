package com.drift.backend.ais.capture;

public class InvalidRecordedCaptureException extends RuntimeException {

	public InvalidRecordedCaptureException(String message) {
		super(message);
	}

	public InvalidRecordedCaptureException(String message, Throwable cause) {
		super(message, cause);
	}
}
