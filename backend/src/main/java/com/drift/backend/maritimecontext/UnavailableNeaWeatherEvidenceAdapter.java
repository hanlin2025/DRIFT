package com.drift.backend.maritimecontext;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import com.drift.backend.shipment.ShipmentDetailResponse;

/** Explicitly exposes the current integration gap rather than fabricating weather context. */
@Component
class UnavailableNeaWeatherEvidenceAdapter implements MaritimeEvidenceSourceAdapter {
	private final boolean curatedSnapshotsEnabled;
	private final boolean controlledReplayEnabled;

	UnavailableNeaWeatherEvidenceAdapter() { this(false, false); }

	UnavailableNeaWeatherEvidenceAdapter(boolean curatedSnapshotsEnabled) { this(curatedSnapshotsEnabled, false); }

	@Autowired
	UnavailableNeaWeatherEvidenceAdapter(
			@Value("${app.maritime-context.curated-snapshots-enabled:false}") boolean curatedSnapshotsEnabled,
			@Value("${app.maritime-context.controlled-replay-enabled:false}") boolean controlledReplayEnabled) {
		this.curatedSnapshotsEnabled = curatedSnapshotsEnabled;
		this.controlledReplayEnabled = controlledReplayEnabled;
	}

	@Override
	public List<MaritimeEvidence> forShipment(ShipmentDetailResponse shipment, Instant cutoff) {
		// A configured curated source is allowed to be absent for a particular vessel; do not
		// add a contradictory "unavailable" weather row beside a real curated observation.
		if (curatedSnapshotsEnabled || controlledReplayEnabled) return List.of();
		return List.of(new MaritimeEvidence(
				"NEA_WEATHER", "UNAVAILABLE", "nea-weather-not-ingested", "Live NEA weather context not ingested",
				"NEA public weather API", "", "No persistent, point-in-time weather snapshot has been ingested into DRIFT.",
				null, null, null, "UNAVAILABLE", "", "", "", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN",
				false, true, "UNAVAILABLE", "Live NEA observations and forecasts are not ingested. This is separate from any explicitly labelled synthetic replay weather evidence; no weather causation is claimed."));
	}
}
