package com.drift.backend.maritimechange;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares curated MPA expected-arrival revisions, MPA notices, and NEA regional weather.
 * The caller supplies the normalized snapshots. This service does not read a collector directory.
 * An identical fingerprint is stored once. A historical backfill is never labeled as a live observation.
 */
@Service
public class MaritimeSnapshotChangeDetector {

	static final String MPA_ETA_REVISION = "MPA_ETA_REVISION";
	static final String MPA_OFFICIAL_NOTICE = "MPA_OFFICIAL_NOTICE";
	static final String NEA_WEATHER = "NEA_WEATHER";
	static final String LIVE = "LIVE";
	static final String RECORDED_REPLAY = "RECORDED_REPLAY";
	static final String SYNTHETIC_DEMONSTRATION = "SYNTHETIC_DEMONSTRATION";
	static final String HISTORICAL_BACKFILL = "HISTORICAL_BACKFILL";
	static final String APPLICABLE = "APPLICABLE";
	static final String HISTORICAL = "HISTORICAL";
	static final String UNKNOWN = "UNKNOWN";
	static final String COMPATIBLE = "COMPATIBLE";
	static final String INCOMPATIBLE = "INCOMPATIBLE";
	static final String NEW = "NEW";
	static final String REVISED = "REVISED";
	static final String FORWARD = "FORWARD";
	static final String OUT_OF_ORDER = "OUT_OF_ORDER";
	static final String BACKFILL = "BACKFILL";

	private static final Set<String> SOURCE_TYPES = Set.of(MPA_ETA_REVISION, MPA_OFFICIAL_NOTICE, NEA_WEATHER);
	private static final Set<String> SOURCE_MODES = Set.of(LIVE, RECORDED_REPLAY, SYNTHETIC_DEMONSTRATION, HISTORICAL_BACKFILL);
	private static final Set<String> FRESHNESS = Set.of(APPLICABLE, HISTORICAL, UNKNOWN);
	private static final Set<String> COMPATIBILITY = Set.of(COMPATIBLE, INCOMPATIBLE, UNKNOWN);
	private static final int IDENTITY_LIMIT = 300;
	private static final int TEXT_LIMIT = 4000;

	private final MaritimeSnapshotObservationStore store;
	private final Clock clock;
	private final ApplicationEventPublisher events;

	@Autowired
	public MaritimeSnapshotChangeDetector(MaritimeSnapshotObservationStore store, ApplicationEventPublisher events) {
		this(store, Clock.systemUTC(), events);
	}

	MaritimeSnapshotChangeDetector(MaritimeSnapshotObservationStore store) {
		this(store, Clock.systemUTC(), null);
	}

	MaritimeSnapshotChangeDetector(MaritimeSnapshotObservationStore store, Clock clock) {
		this(store, clock, null);
	}

	MaritimeSnapshotChangeDetector(MaritimeSnapshotObservationStore store, Clock clock,
			ApplicationEventPublisher events) {
		this.store = store;
		this.clock = clock;
		this.events = events;
	}

	@Transactional
	public MaritimeSnapshotChangeBatch accept(List<MaritimeSnapshot> snapshots) {
		if (snapshots == null) {
			throw new IllegalArgumentException("Snapshots are required");
		}
		List<MaritimeSnapshotChange> changes = new ArrayList<>();
		List<MaritimeSnapshotRejection> rejections = new ArrayList<>();
		for (MaritimeSnapshot snapshot : snapshots) {
			Prepared prepared = prepare(snapshot);
			if (prepared.rejection() != null) {
				rejections.add(prepared.rejection());
				continue;
			}
			record(prepared).ifPresent(change -> {
				changes.add(change);
				if (events != null) events.publishEvent(new MaritimeSnapshotChangeDetectedEvent(change));
			});
		}
		return new MaritimeSnapshotChangeBatch(List.copyOf(changes), List.copyOf(rejections));
	}

	private Optional<MaritimeSnapshotChange> record(Prepared prepared) {
		Optional<MaritimeSnapshotObservation> head = store.head(prepared.sourceType(), prepared.sourceIdentity());
		if (head.isPresent() && prepared.currentValue().equals(head.get().getCurrentValue())) {
			return Optional.empty();
		}
		boolean older = head.isPresent() && (prepared.retrievedAt().isBefore(head.get().getRetrievedAtUtc())
				|| observedBefore(prepared.observedAt(), head.get().getObservedAt()));
		if (head.isPresent() && HISTORICAL.equals(prepared.freshness())
				&& sourceEventBefore(prepared.sourceEventAt(), head.get().getSourceEventAt())) {
			older = true;
		}
		String ordering;
		boolean becomesHead;
		boolean forwardLooking;
		String freshness;
		String previousValue = null;
		String fingerprint = prepared.fingerprint();
		if (HISTORICAL_BACKFILL.equals(prepared.sourceMode())) {
			ordering = BACKFILL;
			becomesHead = !older;
			forwardLooking = false;
			freshness = HISTORICAL;
		} else if (older) {
			ordering = OUT_OF_ORDER;
			becomesHead = false;
			forwardLooking = false;
			freshness = HISTORICAL;
		} else {
			ordering = FORWARD;
			becomesHead = true;
			freshness = prepared.freshness();
			forwardLooking = LIVE.equals(prepared.sourceMode()) && APPLICABLE.equals(freshness);
			previousValue = head.map(MaritimeSnapshotObservation::getCurrentValue).orElse(prepared.previousValue());
			fingerprint = MaritimeSnapshotFingerprint.of(prepared.sourceType(), prepared.sourceIdentity(),
					prepared.vesselImo(), prepared.location(), previousValue, prepared.currentValue(),
					prepared.sourceEventAt(), prepared.effectiveFrom(), prepared.effectiveUntil(),
					head.map(MaritimeSnapshotObservation::getContentFingerprint).orElse(null));
		}
		if (store.find(prepared.sourceType(), prepared.sourceIdentity(), fingerprint).isPresent()) {
			return Optional.empty();
		}
		String kind = head.isEmpty() ? NEW : REVISED;
		Instant recordedAt = clock.instant();
		store.add(MaritimeSnapshotObservation.record(prepared.sourceType(), prepared.sourceIdentity(), fingerprint,
				kind, ordering, forwardLooking, prepared.provenance(), prepared.sourceEventAt(), prepared.observedAt(),
				prepared.retrievedAt(), prepared.sourceMode(), freshness, prepared.vesselImo(), prepared.location(),
				prepared.effectiveFromInstant(), prepared.effectiveUntilInstant(), previousValue,
				prepared.currentValue(), prepared.activityCompatibility(), prepared.locationCompatibility(),
				prepared.timeCompatibility(), recordedAt), becomesHead);
		return Optional.of(new MaritimeSnapshotChange(prepared.sourceType(), prepared.sourceIdentity(), fingerprint,
				kind, ordering, forwardLooking, prepared.provenance(), prepared.sourceEventAt(), prepared.observedAt(),
				prepared.retrievedAtUtc(), prepared.retrievedAt(), prepared.sourceMode(), freshness, prepared.vesselImo(),
				prepared.location(), prepared.effectiveFrom(), prepared.effectiveUntil(), previousValue,
				prepared.currentValue(), prepared.activityCompatibility(), prepared.locationCompatibility(),
				prepared.timeCompatibility(), recordedAt));
	}

	private static boolean observedBefore(String incoming, String stored) {
		Instant incomingAt = parseInstant(incoming);
		Instant storedAt = parseInstant(stored);
		return incomingAt != null && storedAt != null && incomingAt.isBefore(storedAt);
	}

	private static boolean sourceEventBefore(String incoming, String stored) {
		return observedBefore(incoming, stored);
	}

	private Prepared prepare(MaritimeSnapshot snapshot) {
		if (snapshot == null) {
			return Prepared.rejected("", "SNAPSHOT");
		}
		String identity = trim(snapshot.sourceIdentity());
		String reportedIdentity = identity == null ? "" : identity;
		if (identity == null || identity.length() > IDENTITY_LIMIT) {
			return Prepared.rejected(reportedIdentity, "SOURCE_IDENTITY");
		}
		String sourceType = trim(snapshot.sourceType());
		if (!SOURCE_TYPES.contains(sourceType)) {
			return Prepared.rejected(identity, "SOURCE_TYPE");
		}
		String sourceMode = trim(snapshot.sourceMode());
		if (!SOURCE_MODES.contains(sourceMode)) {
			return Prepared.rejected(identity, "SOURCE_MODE");
		}
		String provenance = trim(snapshot.provenance());
		if (provenance == null || provenance.length() > TEXT_LIMIT) {
			return Prepared.rejected(identity, "PROVENANCE");
		}
		String retrievedText = trim(snapshot.retrievedAtUtc());
		Instant retrievedAt = parseInstant(retrievedText);
		if (retrievedAt == null) {
			return Prepared.rejected(identity, "RETRIEVED_AT");
		}
		String freshness = trim(snapshot.freshness());
		if (freshness != null && !FRESHNESS.contains(freshness)) {
			return Prepared.rejected(identity, "FRESHNESS");
		}
		if (freshness == null) {
			freshness = LIVE.equals(sourceMode) ? APPLICABLE : UNKNOWN;
		}
		String vesselImo = trim(snapshot.vesselImo());
		if (vesselImo != null && !MaritimeImo.valid(vesselImo)) {
			return Prepared.rejected(identity, "IMO");
		}
		if (MPA_ETA_REVISION.equals(sourceType) && vesselImo == null) {
			return Prepared.rejected(identity, "IMO");
		}
		String location = trim(snapshot.location());
		if (NEA_WEATHER.equals(sourceType) && (location == null || location.length() > TEXT_LIMIT)) {
			return Prepared.rejected(identity, "LOCATION");
		}
		if (location != null && location.length() > TEXT_LIMIT) {
			return Prepared.rejected(identity, "LOCATION");
		}
		String currentValue = trim(snapshot.currentValue());
		String previousValue = trim(snapshot.previousValue());
		if (currentValue == null || currentValue.length() > TEXT_LIMIT || (previousValue != null && previousValue.length() > TEXT_LIMIT)) {
			return Prepared.rejected(identity, "CONTENT");
		}
		String sourceEventAt = trim(snapshot.sourceEventAt());
		String observedAt = trim(snapshot.observedAt());
		if ((sourceEventAt != null && sourceEventAt.length() > TEXT_LIMIT) || (observedAt != null && observedAt.length() > TEXT_LIMIT)) {
			return Prepared.rejected(identity, "CONTENT");
		}
		String effectiveFrom = trim(snapshot.effectiveFrom());
		String effectiveUntil = trim(snapshot.effectiveUntil());
		Instant effectiveFromInstant = null;
		Instant effectiveUntilInstant = null;
		if (effectiveFrom != null) {
			effectiveFromInstant = parseInstant(effectiveFrom);
			if (effectiveFromInstant == null) {
				return Prepared.rejected(identity, "EFFECTIVE_PERIOD");
			}
		}
		if (effectiveUntil != null) {
			effectiveUntilInstant = parseInstant(effectiveUntil);
			if (effectiveUntilInstant == null || (effectiveFromInstant != null && effectiveUntilInstant.isBefore(effectiveFromInstant))) {
				return Prepared.rejected(identity, "EFFECTIVE_PERIOD");
			}
		}
		String activity = compatibility(snapshot.activityCompatibility());
		String locationCompatibility = compatibility(snapshot.locationCompatibility());
		String time = compatibility(snapshot.timeCompatibility());
		if (activity == Prepared.INVALID || locationCompatibility == Prepared.INVALID || time == Prepared.INVALID) {
			return Prepared.rejected(identity, "COMPATIBILITY");
		}
		String fingerprint = MaritimeSnapshotFingerprint.of(sourceType, identity, vesselImo, location, previousValue, currentValue,
				sourceEventAt, effectiveFrom, effectiveUntil);
		return new Prepared(null, sourceType, identity, provenance, sourceEventAt, observedAt, retrievedText, retrievedAt,
				sourceMode, freshness, vesselImo, location, effectiveFrom, effectiveUntil, effectiveFromInstant,
				effectiveUntilInstant, previousValue, currentValue, activity, locationCompatibility, time, fingerprint);
	}

	private static String compatibility(String value) {
		String trimmed = trim(value);
		if (trimmed == null) {
			return null;
		}
		return COMPATIBILITY.contains(trimmed) ? trimmed : Prepared.INVALID;
	}

	private static Instant parseInstant(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private static String trim(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private record Prepared(
			MaritimeSnapshotRejection rejection,
			String sourceType,
			String sourceIdentity,
			String provenance,
			String sourceEventAt,
			String observedAt,
			String retrievedAtUtc,
			Instant retrievedAt,
			String sourceMode,
			String freshness,
			String vesselImo,
			String location,
			String effectiveFrom,
			String effectiveUntil,
			Instant effectiveFromInstant,
			Instant effectiveUntilInstant,
			String previousValue,
			String currentValue,
			String activityCompatibility,
			String locationCompatibility,
			String timeCompatibility,
			String fingerprint) {

		private static final String INVALID = "\u0000";

		private static Prepared rejected(String sourceIdentity, String reason) {
			return new Prepared(new MaritimeSnapshotRejection(sourceIdentity, reason), null, null, null, null, null, null,
					null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
		}
	}
}
