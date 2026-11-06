package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.drift.backend.ais.operation.OperationalStateService;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Compares the mother vessel's latest retained report with that vessel's own earlier reports near the
 * transshipment port. Calculated when the shipment is read and not stored. This is not a measured congestion index.
 */
@Schema(description = "Comparison of the mother vessel's latest retained report with that vessel's own earlier reports within 25 nautical miles of Singapore or Tanjung Pelepas. Calculated when the shipment is read and not stored. It is not a measured congestion index and it does not replace the operational state or the AIS feed status. MATCHES_NORMAL stays distinct from an unavailable comparison. A position more than six hours old is not used. A position that is exactly six hours old can be used. The feed status does not by itself make this unavailable.")
public record PortConditionComparison(
		@Schema(description = "Completed result. Null when the comparison cannot be made.", nullable = true, example = "MATCHES_NORMAL")
		Outcome outcome,
		@Schema(description = "Why the comparison cannot be made. Null when outcome is present.", nullable = true, example = "NO_BASELINE")
		Reason reason,
		@Schema(description = "Wording for the outcome or the single blocking reason.")
		String message,
		@Schema(description = "The vessel this comparison is about. The feeder vessel is not compared.", example = "MOTHER")
		ComparedVessel vessel,
		@Schema(description = "Stored mother-vessel name. Null when the shipment has none.", nullable = true, example = "Ever Steady")
		String vesselName,
		@Schema(description = "Stored transshipment port. Null when the port is missing.", nullable = true, example = "Singapore")
		String transshipmentPort,
		@Schema(description = "Singapore or Tanjung Pelepas when the stored port is one of those. Null otherwise.", nullable = true, example = "Singapore")
		String knownPort,
		@Schema(description = "Latitude of the latest retained report. Null when that report was not used.", nullable = true, example = "1.264")
		BigDecimal latitude,
		@Schema(description = "Longitude of the latest retained report. Null when that report was not used.", nullable = true, example = "103.840")
		BigDecimal longitude,
		@Schema(description = "Speed of the latest retained report, in knots. Null when that report has no speed or was not used.", nullable = true, example = "8.0")
		BigDecimal speedOverGroundKnots,
		@Schema(description = "Navigational status of the latest retained report. Null when the report has none or was not used.", nullable = true, example = "0")
		Integer navigationalStatus,
		@Schema(description = "When DRIFT stored the latest retained report. Null when no report was used.", nullable = true)
		Instant ingestedAt,
		@Schema(description = "Source of the latest retained report. Null when no report was used.", nullable = true, example = "AIS_STREAM")
		AisPositionSource source,
		@Schema(description = "Earlier near-port reports whose speeds were compared. Zero unless a result was completed.", example = "1")
		int earlierReportCount,
		@Schema(description = "Slowest speed among those earlier reports, in knots. Null unless a result was completed.", nullable = true, example = "4.0")
		BigDecimal slowestOwnSpeedKnots) {

	static final String MATCHES = "The mother vessel's movement matches its own retained reports near this port. This is not a measured congestion index.";
	static final String SLOWER = "The mother vessel is moving slower than its own retained reports near this port. This is not a measured congestion index.";
	static final String NO_REPORT_MESSAGE = "The mother vessel has no retained AIS report.";
	static final String MISSING_PORT_MESSAGE = "The transshipment port is missing.";
	static final String PORT_NOT_DEFINED_MESSAGE = "Normal conditions are not defined for this port.";
	static final String UNUSABLE_POSITION_MESSAGE = "The mother vessel's coordinates cannot be used.";
	static final String NOT_NEAR_PORT_MESSAGE = "The mother vessel is not within 25 nautical miles of the transshipment port, so current port conditions are not observed.";
	static final String UNRELIABLE_REPORT_MESSAGE = "The latest report is unreliable because a stopped status was reported above 1 knot.";
	static final String POSITION_TOO_OLD_MESSAGE = "The mother vessel's position is more than six hours old.";
	static final String MISSING_SPEED_MESSAGE = "The mother vessel's speed is missing, so it cannot be compared with its own reports.";
	static final String NO_BASELINE_MESSAGE = "This vessel has no earlier report near this port, so normal conditions are not defined.";
	static final String BASELINE_TRUNCATED_MESSAGE = "This vessel has more retained reports than this comparison reads, so normal conditions are not defined from a partial history.";

	public enum Outcome {
		MATCHES_NORMAL,
		SLOWER_THAN_OWN_REPORTS
	}

	public enum Reason {
		NO_REPORT,
		MISSING_PORT,
		PORT_NOT_DEFINED,
		UNUSABLE_POSITION,
		NOT_NEAR_PORT,
		UNRELIABLE_REPORT,
		POSITION_TOO_OLD,
		MISSING_SPEED,
		NO_BASELINE,
		BASELINE_TRUNCATED
	}

	public enum ComparedVessel {
		MOTHER
	}

	public PortConditionComparison {
		if (vessel != ComparedVessel.MOTHER) {
			throw new IllegalArgumentException("The port-condition comparison is for the mother vessel");
		}
		if (message == null || message.isBlank()) {
			throw new IllegalArgumentException("The port-condition comparison names its result");
		}
		boolean completed = outcome != null;
		if (completed == (reason != null)) {
			throw new IllegalArgumentException("The port-condition comparison has one outcome or one reason");
		}
		if (completed) {
			if (!message.equals(outcome == Outcome.MATCHES_NORMAL ? MATCHES : SLOWER)) {
				throw new IllegalArgumentException("The completed comparison uses its recorded wording");
			}
			if (earlierReportCount < 1 || slowestOwnSpeedKnots == null || speedOverGroundKnots == null
					|| ingestedAt == null || knownPort == null || latitude == null || longitude == null
					|| source == null) {
				throw new IllegalArgumentException("A completed comparison keeps the report and the speeds it used");
			}
			int compared = speedOverGroundKnots.compareTo(slowestOwnSpeedKnots);
			if (outcome == Outcome.MATCHES_NORMAL && compared < 0) {
				throw new IllegalArgumentException("A match is not slower than this vessel's own reports");
			}
			if (outcome == Outcome.SLOWER_THAN_OWN_REPORTS && compared >= 0) {
				throw new IllegalArgumentException("A slower result is below this vessel's own reports");
			}
		} else if (earlierReportCount != 0 || slowestOwnSpeedKnots != null || !message.equals(sentence(reason))) {
			throw new IllegalArgumentException("An unavailable comparison names why it was not made");
		}
		if ((latitude == null) != (longitude == null)) {
			throw new IllegalArgumentException("A report position keeps both coordinates");
		}
		if (knownPort != null && !"Singapore".equals(knownPort) && !"Tanjung Pelepas".equals(knownPort)) {
			throw new IllegalArgumentException("The known port is Singapore or Tanjung Pelepas");
		}
	}

	/**
	 * Decides the comparison from the mother vessel's latest report and earlier reports already retained for that MMSI.
	 * {@code historyTruncated} means the read stopped before every earlier report, so no partial baseline is used.
	 */
	static PortConditionComparison of(String vesselName, String transshipmentPort, AisPosition latest,
			List<AisPosition> earlier, boolean historyTruncated, Instant now, OperationalStateService ports) {
		if (now == null || ports == null) {
			throw new IllegalArgumentException("A clock and the port rule are required");
		}
		String port = blank(transshipmentPort) ? null : transshipmentPort;
		String knownPort = ports.knownPortName(port);
		if (latest == null) {
			return unavailable(Reason.NO_REPORT, vesselName, port, knownPort, null);
		}
		if (port == null) {
			return unavailable(Reason.MISSING_PORT, vesselName, null, null, latest);
		}
		if (knownPort == null) {
			return unavailable(Reason.PORT_NOT_DEFINED, vesselName, port, null, latest);
		}
		if (!ports.coordinatesUsable(latest.latitude(), latest.longitude()) || latest.ingestedAt() == null
				|| latest.source() == null) {
			return unavailable(Reason.UNUSABLE_POSITION, vesselName, port, knownPort, latest);
		}
		if (ports.reportedMovementConflict(latest.navigationalStatus(), latest.speedOverGroundKnots())) {
			return unavailable(Reason.UNRELIABLE_REPORT, vesselName, port, knownPort, latest);
		}
		if (!ports.nearKnownPort(latest.latitude(), latest.longitude(), port)) {
			return unavailable(Reason.NOT_NEAR_PORT, vesselName, port, knownPort, latest);
		}
		if (Duration.between(latest.ingestedAt(), now).compareTo(ArrivalEstimateCalculator.OUTDATED_AFTER) > 0) {
			return unavailable(Reason.POSITION_TOO_OLD, vesselName, port, knownPort, latest);
		}
		if (latest.speedOverGroundKnots() == null) {
			return unavailable(Reason.MISSING_SPEED, vesselName, port, knownPort, latest);
		}
		if (historyTruncated) {
			return unavailable(Reason.BASELINE_TRUNCATED, vesselName, port, knownPort, latest);
		}
		BigDecimal slowest = null;
		int count = 0;
		if (earlier != null) {
			for (AisPosition report : earlier) {
				if (!counts(report, latest, port, ports)) {
					continue;
				}
				count++;
				if (slowest == null || report.speedOverGroundKnots().compareTo(slowest) < 0) {
					slowest = report.speedOverGroundKnots();
				}
			}
		}
		if (count == 0) {
			return unavailable(Reason.NO_BASELINE, vesselName, port, knownPort, latest);
		}
		if (latest.speedOverGroundKnots().compareTo(slowest) >= 0) {
			return completed(Outcome.MATCHES_NORMAL, MATCHES, vesselName, port, knownPort, latest, count, slowest);
		}
		return completed(Outcome.SLOWER_THAN_OWN_REPORTS, SLOWER, vesselName, port, knownPort, latest, count, slowest);
	}

	private static boolean counts(AisPosition report, AisPosition latest, String port, OperationalStateService ports) {
		if (report == null || report.ingestedAt() == null || !report.ingestedAt().isBefore(latest.ingestedAt())) {
			return false;
		}
		if (Boolean.FALSE.equals(report.positionValid()) || report.speedOverGroundKnots() == null) {
			return false;
		}
		return ports.nearKnownPort(report.latitude(), report.longitude(), port);
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String sentence(Reason reason) {
		return switch (reason) {
			case NO_REPORT -> NO_REPORT_MESSAGE;
			case MISSING_PORT -> MISSING_PORT_MESSAGE;
			case PORT_NOT_DEFINED -> PORT_NOT_DEFINED_MESSAGE;
			case UNUSABLE_POSITION -> UNUSABLE_POSITION_MESSAGE;
			case NOT_NEAR_PORT -> NOT_NEAR_PORT_MESSAGE;
			case UNRELIABLE_REPORT -> UNRELIABLE_REPORT_MESSAGE;
			case POSITION_TOO_OLD -> POSITION_TOO_OLD_MESSAGE;
			case MISSING_SPEED -> MISSING_SPEED_MESSAGE;
			case NO_BASELINE -> NO_BASELINE_MESSAGE;
			case BASELINE_TRUNCATED -> BASELINE_TRUNCATED_MESSAGE;
		};
	}

	private static PortConditionComparison completed(Outcome outcome, String message, String vesselName, String port,
			String knownPort, AisPosition latest, int count, BigDecimal slowest) {
		return new PortConditionComparison(outcome, null, message, ComparedVessel.MOTHER, vesselName, port, knownPort,
				latest.latitude(), latest.longitude(), latest.speedOverGroundKnots(), latest.navigationalStatus(),
				latest.ingestedAt(), latest.source(), count, slowest);
	}

	private static PortConditionComparison unavailable(Reason reason, String vesselName, String port, String knownPort,
			AisPosition latest) {
		boolean positioned = latest != null && latest.latitude() != null && latest.longitude() != null;
		return new PortConditionComparison(null, reason, sentence(reason), ComparedVessel.MOTHER, vesselName, port,
				knownPort, positioned ? latest.latitude() : null, positioned ? latest.longitude() : null,
				latest == null ? null : latest.speedOverGroundKnots(),
				latest == null ? null : latest.navigationalStatus(), latest == null ? null : latest.ingestedAt(),
				latest == null ? null : latest.source(), 0, null);
	}
}
