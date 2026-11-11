package com.drift.backend.maritimecontext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.drift.backend.maritimechange.MaritimeSnapshotChange;
import com.drift.backend.maritimechange.MaritimeSnapshotChangeDetector;
import com.drift.backend.maritimechange.MaritimeSnapshotEvidenceReader;
import com.drift.backend.maritimechange.MaritimeSnapshotShipmentMatcher;
import com.drift.backend.shipment.ShipmentDetailResponse;

/**
 * Projects persisted MPA expected-arrival heads into the ordinary evidence bundle.
 * The reader retains only the current head for each source identity, so a head retrieved
 * after the assessment cutoff is omitted. That omission does not reconstruct an earlier
 * superseded snapshot. An arrival that crosses local midnight is a new collector identity,
 * not a revision of the previous identity.
 */
@Component
class PersistedMpaEtaEvidenceAdapter implements MaritimeEvidenceSourceAdapter {
	static final String HEAD_ONLY_CUTOFF_LIMITATION = "Only the current persisted head is available. A head retrieved after "
			+ "the assessment cutoff is omitted, and an earlier superseded snapshot is not reconstructed.";
	static final String DATE_IDENTITY_LIMITATION = "An expected arrival that crosses local midnight is stored under a new "
			+ "source identity and is not a revision of the previous identity.";

	private final MaritimeSnapshotEvidenceReader snapshots;
	private final MaritimeSnapshotShipmentMatcher matcher;

	PersistedMpaEtaEvidenceAdapter(MaritimeSnapshotEvidenceReader snapshots, MaritimeSnapshotShipmentMatcher matcher) {
		this.snapshots = snapshots;
		this.matcher = matcher;
	}

	@Override
	public List<MaritimeEvidence> forShipment(ShipmentDetailResponse shipment, Instant cutoff) {
		if (shipment == null || cutoff == null) return List.of();
		Map<String, MaritimeEvidence> byId = new LinkedHashMap<>();
		for (MaritimeSnapshotEvidenceReader.Snapshot snapshot : snapshots.heads("MPA_ETA_REVISION")) {
			if (!isDisplayable(snapshot) || snapshot.retrievedAtUtc() == null || snapshot.retrievedAtUtc().isAfter(cutoff)) {
				continue;
			}
			MaritimeEvidence evidence = toEvidence(snapshot, shipment);
			if (evidence != null) byId.putIfAbsent(evidence.evidenceId(), evidence);
		}
		return List.copyOf(byId.values());
	}

	private static boolean isDisplayable(MaritimeSnapshotEvidenceReader.Snapshot snapshot) {
		String mode = snapshot.sourceMode();
		return MaritimeSnapshotChangeDetector.LIVE.equals(mode) || "RECORDED_REPLAY".equals(mode)
				|| "HISTORICAL_BACKFILL".equals(mode);
	}

	private MaritimeEvidence toEvidence(MaritimeSnapshotEvidenceReader.Snapshot snapshot, ShipmentDetailResponse shipment) {
		MaritimeSnapshotShipmentMatcher.EvidenceAssociation association = matcher.associate(asChange(snapshot),
				shipment.transshipmentPort(), shipment.motherVesselImo(), shipment.feederVesselImo(),
				shipment.plannedMotherArrivalAt(), shipment.plannedFeederDepartureAt());
		if (!association.included() && isUnrelated(association.exclusionReason())) return null;
		ParsedValue parsed = parse(snapshot.currentValue());
		String freshness = freshness(snapshot);
		boolean incompatible = !association.included();
		boolean sufficient = association.included() && "APPLICABLE".equals(freshness);
		String role = "MOTHER".equals(association.role()) || "FEEDER".equals(association.role()) ? association.role() : null;
		String id = "mpa-eta:" + snapshot.sourceIdentity() + ":" + snapshot.contentFingerprint();
		String citation = "MPA OCEANS-X expected arrival; source identity: " + snapshot.sourceIdentity()
				+ "; fingerprint: " + snapshot.contentFingerprint()
				+ "; retrieved: " + snapshot.retrievedAtUtc()
				+ (snapshot.provenance() == null || snapshot.provenance().isBlank() ? "" : "; provenance: " + snapshot.provenance());
		return new MaritimeEvidence("MPA_ETA_REVISION", snapshot.sourceMode(), id,
				"Persisted MPA expected arrival", snapshot.sourceIdentity(), "", citation, snapshot.sourceEventAt(),
				snapshot.observedAt(), snapshot.retrievedAtUtc().toString(), freshness, snapshot.previousValue(),
				snapshot.currentValue(), null, unknown(association.activity()),
				incompatible && MaritimeSnapshotShipmentMatcher.INCOMPATIBLE.equals(association.exclusionReason())
						? "INCOMPATIBLE" : unknown(association.location()),
				incompatible && MaritimeSnapshotShipmentMatcher.OUT_OF_WINDOW.equals(association.exclusionReason())
						? "INCOMPATIBLE" : unknown(association.time()),
				incompatible ? "INCOMPATIBLE" : compatibleState(association), sufficient, true,
				association.association() == null ? "UNVERIFIED_IMO_ONLY" : association.association(),
				limitation(snapshot, parsed, freshness), snapshot.vesselImo(), role, "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION");
	}

	private static boolean isUnrelated(String reason) {
		return MaritimeSnapshotShipmentMatcher.NOT_ASSOCIATED.equals(reason)
				|| MaritimeSnapshotShipmentMatcher.OUTSIDE_REGION.equals(reason);
	}

	private static String freshness(MaritimeSnapshotEvidenceReader.Snapshot snapshot) {
		if ("HISTORICAL_BACKFILL".equals(snapshot.sourceMode()) || "HISTORICAL".equals(snapshot.freshness())) return "HISTORICAL";
		if (!"APPLICABLE".equals(snapshot.freshness())) return "UNKNOWN";
		return "APPLICABLE";
	}

	private static String compatibleState(MaritimeSnapshotShipmentMatcher.EvidenceAssociation association) {
		if ("COMPATIBLE".equals(association.location()) && "COMPATIBLE".equals(association.time())) return "COMPATIBLE";
		return "UNKNOWN";
	}

	private static String limitation(MaritimeSnapshotEvidenceReader.Snapshot snapshot, ParsedValue parsed, String freshness) {
		String arrival = parsed.arrival().isBlank() ? "unparsed" : parsed.arrival();
		String port = parsed.port().isBlank() ? "unparsed" : parsed.port();
		return "Persisted MPA expected-arrival value for IMO " + snapshot.vesselImo()
				+ "; arrival " + arrival + "; port " + port + ". "
				+ "An IMO match does not verify a shipment booking, port call, or delay cause. "
				+ ("APPLICABLE".equals(freshness) ? "Human review is required. " : "This observation is historical or not currently applicable. ")
				+ HEAD_ONLY_CUTOFF_LIMITATION + " " + DATE_IDENTITY_LIMITATION;
	}

	static ParsedValue parse(String value) {
		Map<String, String> fields = new LinkedHashMap<>();
		if (value != null) {
			for (String part : value.split(";", -1)) {
				int separator = part.indexOf('=');
				if (separator > 0) fields.put(part.substring(0, separator), part.substring(separator + 1));
			}
		}
		return new ParsedValue(fields.getOrDefault("arrival", ""), fields.getOrDefault("departure", ""),
				fields.getOrDefault("port", ""));
	}

	record ParsedValue(String arrival, String departure, String port) { }

	private static MaritimeSnapshotChange asChange(MaritimeSnapshotEvidenceReader.Snapshot snapshot) {
		return new MaritimeSnapshotChange(snapshot.sourceType(), snapshot.sourceIdentity(), snapshot.contentFingerprint(),
				"HEAD", "CURRENT", false, snapshot.provenance() == null ? "" : snapshot.provenance(), snapshot.sourceEventAt(),
				snapshot.observedAt(), snapshot.retrievedAtUtc().toString(), snapshot.retrievedAtUtc(), snapshot.sourceMode(),
				snapshot.freshness(), snapshot.vesselImo(), snapshot.location(), instant(snapshot.effectiveFrom()),
				instant(snapshot.effectiveUntil()), snapshot.previousValue(), snapshot.currentValue(),
				snapshot.activityCompatibility(), snapshot.locationCompatibility(), snapshot.timeCompatibility(),
				snapshot.retrievedAtUtc());
	}

	private static String instant(Instant value) { return value == null ? null : value.toString(); }

	private static String unknown(String value) { return value == null || value.isBlank() ? "UNKNOWN" : value; }
}
