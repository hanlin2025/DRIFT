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

	UnavailableNeaWeatherEvidenceAdapter() { this(false); }

	@Autowired
	UnavailableNeaWeatherEvidenceAdapter(
			@Value("${app.maritime-context.curated-snapshots-enabled:false}") boolean curatedSnapshotsEnabled) {
		this.curatedSnapshotsEnabled = curatedSnapshotsEnabled;
	}

	@Override
	public List<MaritimeEvidence> forShipment(ShipmentDetailResponse shipment, Instant cutoff) {
		// A configured curated source is allowed to be absent for a particular vessel; do not
		// add a contradictory "unavailable" weather row beside a real curated observation.
		if (curatedSnapshotsEnabled) return List.of();
		return List.of(new MaritimeEvidence(
				"NEA_WEATHER", "UNAVAILABLE", "nea-weather-not-ingested", "NEA weather context unavailable",
				"NEA public weather API", "", "No persistent, point-in-time weather snapshot has been ingested into DRIFT.",
				null, null, null, "UNAVAILABLE", "", "", "", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN",
				false, true, "UNAVAILABLE", "Weather observations and forecasts are not inferred from standalone collection outputs. No weather causation is claimed."));
	}
}
