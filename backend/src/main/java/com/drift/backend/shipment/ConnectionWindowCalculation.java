package com.drift.backend.shipment;

public record ConnectionWindowCalculation(Status status, ConnectionWindow window) {

	public enum Status {
		AVAILABLE,
		INCOMPLETE,
		INVALID
	}

	public ConnectionWindowCalculation {
		if (status == null) {
			throw new IllegalArgumentException("Connection window status is required");
		}
		if (status == Status.AVAILABLE && window == null) {
			throw new IllegalArgumentException("An available connection window needs a duration");
		}
		if (status != Status.AVAILABLE && window != null) {
			throw new IllegalArgumentException("Only an available connection window has a duration");
		}
	}

	static ConnectionWindowCalculation available(ConnectionWindow window) {
		return new ConnectionWindowCalculation(Status.AVAILABLE, window);
	}

	static ConnectionWindowCalculation incomplete() {
		return new ConnectionWindowCalculation(Status.INCOMPLETE, null);
	}

	static ConnectionWindowCalculation invalid() {
		return new ConnectionWindowCalculation(Status.INVALID, null);
	}
}
