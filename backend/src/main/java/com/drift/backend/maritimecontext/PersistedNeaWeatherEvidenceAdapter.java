package com.drift.backend.maritimecontext;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.drift.backend.maritimechange.MaritimeSnapshotChangeDetector;
import com.drift.backend.maritimechange.MaritimeSnapshotEvidenceReader;
import com.drift.backend.shipment.ShipmentDetailResponse;

/** Projects the latest persisted NEA forecast per area as regional, read-only evidence. */
@Component
class PersistedNeaWeatherEvidenceAdapter implements MaritimeEvidenceSourceAdapter {
	private static final String RECORDED_REPLAY = "RECORDED_REPLAY";
	private static final String HISTORICAL_BACKFILL = "HISTORICAL_BACKFILL";
	private static final Duration MAX_SOURCE_AGE = Duration.ofHours(6);

	private final MaritimeSnapshotEvidenceReader snapshots;

	PersistedNeaWeatherEvidenceAdapter(MaritimeSnapshotEvidenceReader snapshots) {
		this.snapshots = snapshots;
	}

	@Override
	public List<MaritimeEvidence> forShipment(ShipmentDetailResponse shipment, Instant cutoff) {
		if (!isSingaporePort(shipment)) return List.of();
		return snapshots.heads(MaritimeSnapshotChangeDetector.NEA_WEATHER).stream()
				.filter(snapshot -> !snapshot.retrievedAtUtc().isAfter(cutoff))
				.filter(snapshot -> isDisplayableMode(snapshot.sourceMode()))
				.filter(snapshot -> hasRelevantArea(snapshot.location()))
				.map(snapshot -> toEvidence(snapshot, cutoff))
				.toList();
	}

	private static boolean isDisplayableMode(String sourceMode) {
		// Synthetic A/B evidence has its own isolated adapter and must never leak into ordinary context.
		return MaritimeSnapshotChangeDetector.LIVE.equals(sourceMode) || RECORDED_REPLAY.equals(sourceMode)
				|| HISTORICAL_BACKFILL.equals(sourceMode);
	}

	private static boolean isSingaporePort(ShipmentDetailResponse shipment) {
		return shipment.transshipmentPort() != null
				&& shipment.transshipmentPort().toLowerCase(Locale.ROOT).contains("singapore");
	}

	private static boolean hasRelevantArea(String area) {
		if (area == null || area.isBlank()) return false;
		// NEA forecasts are regional. A Singapore transshipment port makes the selected Singapore
		// forecast areas relevant planning context; this does not associate weather with the vessel.
		return true;
	}

	private static MaritimeEvidence toEvidence(MaritimeSnapshotEvidenceReader.Snapshot snapshot, Instant cutoff) {
		String freshness = freshness(snapshot, cutoff);
		String area = snapshot.location();
		String validPeriod = period(snapshot.effectiveFrom(), snapshot.effectiveUntil());
		String limitation = "NEA regional two-hour forecast for " + area + (validPeriod.isBlank() ? "." : " (" + validPeriod + ").")
				+ " Regional context only; it does not establish weather at a vessel, a shipment-specific impact, or causation. "
				+ ("APPLICABLE".equals(freshness) ? "Human review is required." : "This forecast is historical or outside its valid period.");
		String id = "nea-weather:" + snapshot.sourceIdentity() + ":" + snapshot.contentFingerprint();
		String citation = "NEA two-hour forecast; regional area: " + area
				+ (validPeriod.isBlank() ? "" : "; valid period: " + validPeriod)
				+ (snapshot.provenance() == null || snapshot.provenance().isBlank() ? "" : "; provenance: " + snapshot.provenance());
		return new MaritimeEvidence("NEA_WEATHER", snapshot.sourceMode(), id, "NEA regional two-hour forecast — " + area,
				"NEA two-hour forecast for " + area, "", citation, snapshot.sourceEventAt(), snapshot.observedAt(),
				snapshot.retrievedAtUtc().toString(), freshness, snapshot.previousValue(), snapshot.currentValue(), null,
				unknown(snapshot.activityCompatibility()), unknown(snapshot.locationCompatibility()),
				unknown(snapshot.timeCompatibility()), "UNKNOWN", "APPLICABLE".equals(freshness), true, "REGIONAL_CONTEXT", limitation,
				null, null, "TWO_HOUR_FORECAST", "REGIONAL_CONTEXT");
	}

	private static String freshness(MaritimeSnapshotEvidenceReader.Snapshot snapshot, Instant cutoff) {
		if (HISTORICAL_BACKFILL.equals(snapshot.sourceMode()) || "HISTORICAL".equals(snapshot.freshness())) return "HISTORICAL";
		if (!"APPLICABLE".equals(snapshot.freshness())) return "UNKNOWN";
		if (snapshot.effectiveUntil() != null && snapshot.effectiveUntil().isBefore(cutoff)) return "HISTORICAL";
		Instant sourceTime = parse(snapshot.sourceEventAt());
		if (sourceTime != null && sourceTime.plus(MAX_SOURCE_AGE).isBefore(cutoff)) return "HISTORICAL";
		if (snapshot.retrievedAtUtc().plus(MAX_SOURCE_AGE).isBefore(cutoff)) return "HISTORICAL";
		return "APPLICABLE";
	}

	private static Instant parse(String value) {
		if (value == null) return null;
		try { return Instant.parse(value); }
		catch (DateTimeParseException ex) { return null; }
	}

	private static String period(Instant from, Instant until) {
		if (from == null && until == null) return "";
		return (from == null ? "start unknown" : from.toString()) + " to " + (until == null ? "end unknown" : until.toString());
	}

	private static String unknown(String value) { return value == null ? "UNKNOWN" : value; }
}
