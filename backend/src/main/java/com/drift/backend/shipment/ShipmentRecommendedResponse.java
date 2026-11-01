package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Advice for one completed pair of connection risk and impact. It does not approve, modify,
 * dismiss, change the shipment, or send an email.
 */
@Schema(description = "The recommended response. Advice only: it does not approve, modify, or dismiss, and it does not change the shipment or send an email.")
public record ShipmentRecommendedResponse(
		@Schema(example = "NO_ACTION")
		Code code,
		@Schema(example = "No action")
		String label,
		@Schema(example = "No action is needed. The connection risk is low, and the prediction does not affect the importer's constraints.")
		String wording) {

	public enum Code {
		NO_ACTION("No action",
				"No action is needed. The connection risk is low, and the prediction does not affect the importer's constraints."),
		MONITOR_CONSTRAINTS("Monitor",
				"Monitor the shipment. The connection risk is low, and the prediction affects the importer's constraints."),
		NOTE_TIMING("Note the timing",
				"Note the connection timing. The connection risk is medium, and the prediction does not affect the importer's constraints."),
		REVIEW_PLAN("Review the plan",
				"Review the transfer plan. The connection risk is medium, and the prediction affects the importer's constraints."),
		WATCH_CONNECTION("Watch the connection",
				"Watch the connection. The connection risk is high, and the prediction does not affect the importer's constraints."),
		PREPARE_INTERVENTION("Prepare an intervention",
				"Prepare an intervention. The connection risk is high, and the prediction affects the importer's constraints."),
		CONFIRM_CONNECTION("Confirm the connection",
				"Confirm the connection plan. The connection risk is critical, and the prediction does not affect the importer's constraints."),
		ACT_ON_CONNECTION("Act on the connection",
				"Act on this connection. The connection risk is critical, and the prediction affects the importer's constraints.");

		private final String label;
		private final String wording;

		Code(String label, String wording) {
			this.label = label;
			this.wording = wording;
		}

		public String label() {
			return label;
		}

		public String wording() {
			return wording;
		}
	}

	public ShipmentRecommendedResponse {
		if (code == null) {
			throw new IllegalArgumentException("A recommended response needs a code");
		}
		label = code.label();
		wording = code.wording();
	}

	static ShipmentRecommendedResponse of(Code code) {
		return new ShipmentRecommendedResponse(code, code.label(), code.wording());
	}
}
