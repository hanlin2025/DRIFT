package com.drift.backend.maritimeingest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Reads source time text. A value without an offset is Singapore time, as published by MPA and NEA. */
final class SourceTimes {

	static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
	private static final List<DateTimeFormatter> LOCAL = List.of(
			DateTimeFormatter.ISO_LOCAL_DATE_TIME,
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

	private SourceTimes() {
	}

	/** The instant, or null when the text is missing or not a recognised time. */
	static Instant parse(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String value = text.trim();
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (DateTimeParseException ignored) {
			// Fall through to a local Singapore time.
		}
		for (DateTimeFormatter format : LOCAL) {
			try {
				return LocalDateTime.parse(value, format).atZone(SINGAPORE).toInstant();
			} catch (DateTimeParseException ignored) {
				// Try the next layout.
			}
		}
		try {
			return LocalDate.parse(value).atStartOfDay(SINGAPORE).toInstant();
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}
}
