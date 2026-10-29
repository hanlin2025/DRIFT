package com.drift.backend.maritimecontext;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.drift.backend.shipment.ShipmentDetailResponse;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only curated snapshot adapter.  It deliberately reads a deployment resource, never a
 * collector directory.  Every record is a historical replay: retrieval time is the availability
 * bound, so backfilled data cannot become point-in-time evidence accidentally.
 */
@Component
class CuratedMaritimeEvidenceAdapter implements MaritimeEvidenceSourceAdapter {
	private final boolean enabled;
	private final List<Snapshot> snapshots;

	@Autowired
	CuratedMaritimeEvidenceAdapter(@Value("${app.maritime-context.curated-snapshots-enabled:false}") boolean enabled,
			ObjectMapper json) {
		this(enabled, loadSnapshots(json));
	}

	CuratedMaritimeEvidenceAdapter(boolean enabled, List<Snapshot> snapshots) {
		this.enabled = enabled;
		this.snapshots = List.copyOf(snapshots);
	}

	private static List<Snapshot> loadSnapshots(ObjectMapper json) {
		try (InputStream input = CuratedMaritimeEvidenceAdapter.class
				.getResourceAsStream("/maritime-context/cdg248-curated-evidence.json")) {
			return input == null ? List.of() : json.readValue(input, new TypeReference<List<Snapshot>>() { });
		} catch (Exception ex) { throw new IllegalStateException("Invalid curated maritime evidence resource", ex); }
	}

	@Override public List<MaritimeEvidence> forShipment(ShipmentDetailResponse shipment, Instant cutoff) {
		if (!enabled) return List.of();
		return snapshots.stream().filter(item -> item.imo().equals(shipment.motherVesselImo()))
			.filter(item -> !Instant.parse(item.retrievedAtUtc()).isAfter(cutoff))
			.filter(item -> !"HISTORICAL_BACKFILL".equals(item.collectionLabel()) || "RETROSPECTIVE_ANALYSIS".equals(item.sourceMode()))
			.map(item -> item.toEvidence()).toList();
	}

	public record Snapshot(String sourceType, String sourceMode, String evidenceId, String imo, String title,
			String sourceIdentifier, String citation, String sourceEventAt, String observedAt, String retrievedAtUtc,
			String freshness, String previousValue, String currentValue, String changeMagnitude,
			String locationCompatibility, String collectionLabel, String limitation) {
		MaritimeEvidence toEvidence() { return new MaritimeEvidence(sourceType, sourceMode, evidenceId, title,
				sourceIdentifier, "", citation, sourceEventAt, observedAt, retrievedAtUtc, freshness,
				previousValue, currentValue, changeMagnitude, "UNKNOWN", locationCompatibility, "COMPATIBLE",
				"UNKNOWN", true, true, "UNVERIFIED_IMO_ONLY", limitation); }
	}
}
