package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One existing input that produced the connection risk on this read. It does not restate the hour band or the arrival comparison.")
public record ShipmentRiskContribution(
		@Schema(example = "WINDOW_IN_USE")
		Code code,
		@Schema(example = "Window in use")
		String label,
		@Schema(example = "Remaining connection window")
		String value) {

	public enum Code {
		WINDOW_IN_USE("Window in use"),
		REMAINING_DURATION("Remaining duration"),
		MISSED_BY("Missed by"),
		PLANNED_DURATION("Planned duration"),
		PLANNED_WINDOW("Planned window"),
		ESTIMATE_NOT_USED("Estimate not used");

		private final String label;

		Code(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public ShipmentRiskContribution {
		if (code == null || value == null || value.isBlank()) {
			throw new IllegalArgumentException("A risk contribution needs a code and a value");
		}
		label = code.label();
		if (code == Code.WINDOW_IN_USE
				&& !value.equals(ShipmentRiskContributions.REMAINING_WINDOW)
				&& !value.equals(ShipmentRiskContributions.PLANNED_WINDOW)) {
			throw new IllegalArgumentException("The window in use is the remaining window or the planned window");
		}
		if (code == Code.PLANNED_WINDOW && !value.equals(ShipmentRiskContributions.NOT_POSITIVE)) {
			throw new IllegalArgumentException("A planned window that is not positive has one sentence");
		}
	}

	static ShipmentRiskContribution window(String value) {
		return of(Code.WINDOW_IN_USE, value);
	}

	static ShipmentRiskContribution of(Code code, String value) {
		return new ShipmentRiskContribution(code, code.label(), value);
	}
}
