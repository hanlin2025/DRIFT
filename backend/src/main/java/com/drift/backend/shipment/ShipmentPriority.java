package com.drift.backend.shipment;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ranks a shipment for operational attention by combining how bad its connection looks (severity), how soon
 * the feeder leaves (urgency), and how much evidence backs the assessment (confidence). Calculated on read
 * from the same connection snapshot as {@link ShipmentRisk}, so it never disagrees with the risk level, and
 * not stored. Every reason is built from those inputs only.
 */
@Schema(description = "Priority ranking for operational attention, combining connection severity, urgency to the feeder departure, and confidence in the evidence. Calculated when the shipment is read and not stored.")
public record ShipmentPriority(
		@Schema(description = "Priority from 0 to 100. Higher needs attention sooner.", example = "72")
		int score,
		@Schema(description = "How close the connection is to being missed, from 0 to 1.", example = "0.83")
		double severity,
		@Schema(description = "How soon the feeder departs, from 0 to 1. Zero once the feeder has departed.", example = "0.88")
		double urgency,
		@Schema(description = "How much evidence backs the assessment, from 0 to 1. Live AIS is highest; the planned schedule alone is lower.", example = "1.0")
		double confidence,
		@Schema(description = "Plain-language reasons for this priority, taken only from the inputs above.")
		List<String> reasons) {

	/** A buffer of this many hours or more has no severity; the 12h and 24h risk thresholds sit at 0.75 and 0.5. */
	static final double SEVERITY_HORIZON_HOURS = 48;
	/** Each hour a connection is expected to be missed by adds this much above the missed-connection floor. */
	static final double MISSED_SEVERITY_PER_HOUR = 0.01;
	static final double MISSED_SEVERITY_CAP = 0.2;
	/** Extra severity when the mother vessel is running behind its plan. */
	static final double BEHIND_PLAN_SEVERITY = 0.1;
	/** Urgency decays with a 72-hour time constant to the feeder departure. */
	static final double URGENCY_TIME_CONSTANT_HOURS = 72;
	/** Share of the score that urgency can move; the rest comes from severity alone. */
	static final double URGENCY_WEIGHT = 0.6;
	static final double LIVE_CONFIDENCE = 1.0;
	static final double PLANNED_CONFIDENCE = 0.8;
	static final double OUTDATED_CONFIDENCE = 0.7;
	/** Severity can exceed 1 with the missed and behind-plan boosts, so the score divides by the largest raw value. */
	private static final double MAX_RAW = 1 + MISSED_SEVERITY_CAP + BEHIND_PLAN_SEVERITY;

	public ShipmentPriority {
		reasons = List.copyOf(reasons);
	}

	/**
	 * Returns null when the risk is not assessed, so unassessed shipments rank after every assessed one.
	 */
	static ShipmentPriority of(ConnectionWindowCalculation planned, RemainingConnectionWindow remaining,
			ArrivalEstimate arrival, OffsetDateTime plannedFeederDepartureAt, Instant now) {
		ShipmentRisk risk = ShipmentRisk.of(planned, remaining);
		if (risk == null || plannedFeederDepartureAt == null || now == null) {
			return null;
		}
		List<String> reasons = new ArrayList<>();

		double severity = connectionSeverity(planned, remaining, reasons);
		if (arrival != null && arrival.classification() == ArrivalEstimate.Classification.BEHIND_PLAN) {
			severity += BEHIND_PLAN_SEVERITY;
			reasons.add("The mother vessel is running behind its planned arrival by " + arrival.difference() + ".");
		}

		Duration untilDeparture = Duration.between(now, plannedFeederDepartureAt);
		double urgency;
		if (untilDeparture.isNegative() || untilDeparture.isZero()) {
			urgency = 0;
			reasons.add("The feeder has already departed, so acting now cannot change this connection.");
		} else {
			// Whole hours keep the list and the detail page in step when they are read moments apart.
			long hoursLeft = untilDeparture.toHours();
			urgency = Math.exp(-hoursLeft / URGENCY_TIME_CONSTANT_HOURS);
			reasons.add(hoursLeft == 0 ? "The feeder departs in less than 1 hour."
					: "The feeder departs in " + ConnectionWindow.durationText(Duration.ofHours(hoursLeft)) + ".");
		}

		double confidence;
		if (remaining != null) {
			confidence = LIVE_CONFIDENCE;
			reasons.add("Based on the mother vessel's live AIS position.");
		} else if (arrival != null && Boolean.TRUE.equals(arrival.possiblyOutdated())) {
			confidence = OUTDATED_CONFIDENCE;
			reasons.add("The latest AIS position is possibly outdated, so the planned schedule is used.");
		} else {
			confidence = PLANNED_CONFIDENCE;
			reasons.add("Based on the planned schedule only; no usable AIS estimate is available.");
		}

		double raw = severity * ((1 - URGENCY_WEIGHT) + URGENCY_WEIGHT * urgency) * confidence;
		int score = (int) Math.round(100 * Math.min(1, raw / MAX_RAW));
		return new ShipmentPriority(score, round(Math.min(1, severity)), round(urgency), confidence, reasons);
	}

	private static double connectionSeverity(ConnectionWindowCalculation planned, RemainingConnectionWindow remaining,
			List<String> reasons) {
		if (remaining != null && remaining.status() == RemainingConnectionWindow.Status.MISSED) {
			reasons.add("The connection is expected to be missed by " + remaining.missedBy() + ".");
			return 1 + Math.min(MISSED_SEVERITY_CAP, MISSED_SEVERITY_PER_HOUR * remaining.missedBySeconds() / 3600.0);
		}
		if (remaining == null && planned.status() == ConnectionWindowCalculation.Status.INVALID) {
			reasons.add("The feeder is planned to depart at or before the mother vessel arrives.");
			return 1;
		}
		long bufferSeconds = remaining != null ? remaining.totalSeconds() : planned.window().totalSeconds();
		String buffer = remaining != null ? remaining.duration() : planned.window().duration();
		reasons.add((remaining != null ? "Connection buffer remaining: " : "Planned connection buffer: ") + buffer + ".");
		return Math.max(0, 1 - bufferSeconds / 3600.0 / SEVERITY_HORIZON_HOURS);
	}

	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}
}
