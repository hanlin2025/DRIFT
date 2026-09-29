package com.drift.backend.shipment;

import java.time.Duration;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Planned connection window calculated from the shipment schedule.")
public record ConnectionWindow(
		@Schema(description = "Understandable duration, such as \"1 day 4 hours\". A positive gap under one second is \"less than 1 second\".", example = "1 day 4 hours")
		String duration,
		@Schema(description = "Whole seconds between the planned mother-vessel arrival and the planned feeder-vessel departure.", example = "100800")
		long totalSeconds) {

	static ConnectionWindow of(Duration window) {
		if (window == null || !window.isPositive()) {
			throw new IllegalArgumentException("Connection window must be a positive duration");
		}
		return new ConnectionWindow(describe(window), window.getSeconds());
	}

	private static String describe(Duration window) {
		long days = window.toDays();
		int hours = window.toHoursPart();
		int minutes = window.toMinutesPart();
		int seconds = window.toSecondsPart();
		if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) {
			return "less than 1 second";
		}
		StringBuilder text = new StringBuilder();
		append(text, days, "day");
		append(text, hours, "hour");
		append(text, minutes, "minute");
		append(text, seconds, "second");
		return text.toString();
	}

	private static void append(StringBuilder text, long amount, String unit) {
		if (amount == 0) {
			return;
		}
		if (!text.isEmpty()) {
			text.append(' ');
		}
		text.append(amount).append(' ').append(unit);
		if (amount != 1) {
			text.append('s');
		}
	}
}
