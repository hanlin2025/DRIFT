package com.drift.backend.maritimechange;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One stored fingerprint of a curated maritime snapshot. The current head is the newest retrieval. */
@Entity
@Table(name = "maritime_snapshot_observations")
class MaritimeSnapshotObservation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "source_type", nullable = false, length = 32)
	private String sourceType;

	@Column(name = "source_identity", nullable = false, length = 300)
	private String sourceIdentity;

	@Column(name = "content_fingerprint", nullable = false, length = 64)
	private String contentFingerprint;

	@Column(name = "change_kind", nullable = false, length = 16)
	private String changeKind;

	@Column(nullable = false, length = 16)
	private String ordering;

	@Column(name = "forward_looking", nullable = false)
	private boolean forwardLooking;

	@Column(nullable = false)
	private boolean head;

	@Column(nullable = false, columnDefinition = "text")
	private String provenance;

	@Column(name = "source_event_at", columnDefinition = "text")
	private String sourceEventAt;

	@Column(name = "observed_at", columnDefinition = "text")
	private String observedAt;

	@Column(name = "retrieved_at_utc", nullable = false)
	private Instant retrievedAtUtc;

	@Column(name = "source_mode", nullable = false, length = 32)
	private String sourceMode;

	@Column(nullable = false, length = 32)
	private String freshness;

	@Column(name = "vessel_imo", length = 7)
	private String vesselImo;

	@Column(columnDefinition = "text")
	private String location;

	@Column(name = "effective_from")
	private Instant effectiveFrom;

	@Column(name = "effective_until")
	private Instant effectiveUntil;

	@Column(name = "previous_value", columnDefinition = "text")
	private String previousValue;

	@Column(name = "current_value", columnDefinition = "text")
	private String currentValue;

	@Column(name = "activity_compatibility", length = 16)
	private String activityCompatibility;

	@Column(name = "location_compatibility", length = 16)
	private String locationCompatibility;

	@Column(name = "time_compatibility", length = 16)
	private String timeCompatibility;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	protected MaritimeSnapshotObservation() {
	}

	private MaritimeSnapshotObservation(String sourceType, String sourceIdentity, String contentFingerprint,
			String changeKind, String ordering, boolean forwardLooking, String provenance, String sourceEventAt,
			String observedAt, Instant retrievedAtUtc, String sourceMode, String freshness, String vesselImo,
			String location, Instant effectiveFrom, Instant effectiveUntil, String previousValue, String currentValue,
			String activityCompatibility, String locationCompatibility, String timeCompatibility, Instant recordedAt) {
		this.sourceType = sourceType;
		this.sourceIdentity = sourceIdentity;
		this.contentFingerprint = contentFingerprint;
		this.changeKind = changeKind;
		this.ordering = ordering;
		this.forwardLooking = forwardLooking;
		this.head = false;
		this.provenance = provenance;
		this.sourceEventAt = sourceEventAt;
		this.observedAt = observedAt;
		this.retrievedAtUtc = retrievedAtUtc;
		this.sourceMode = sourceMode;
		this.freshness = freshness;
		this.vesselImo = vesselImo;
		this.location = location;
		this.effectiveFrom = effectiveFrom;
		this.effectiveUntil = effectiveUntil;
		this.previousValue = previousValue;
		this.currentValue = currentValue;
		this.activityCompatibility = activityCompatibility;
		this.locationCompatibility = locationCompatibility;
		this.timeCompatibility = timeCompatibility;
		this.recordedAt = recordedAt;
	}

	static MaritimeSnapshotObservation record(String sourceType, String sourceIdentity, String contentFingerprint,
			String changeKind, String ordering, boolean forwardLooking, String provenance, String sourceEventAt,
			String observedAt, Instant retrievedAtUtc, String sourceMode, String freshness, String vesselImo,
			String location, Instant effectiveFrom, Instant effectiveUntil, String previousValue, String currentValue,
			String activityCompatibility, String locationCompatibility, String timeCompatibility, Instant recordedAt) {
		return new MaritimeSnapshotObservation(sourceType, sourceIdentity, contentFingerprint, changeKind, ordering,
				forwardLooking, provenance, sourceEventAt, observedAt, retrievedAtUtc, sourceMode, freshness, vesselImo,
				location, effectiveFrom, effectiveUntil, previousValue, currentValue, activityCompatibility,
				locationCompatibility, timeCompatibility, recordedAt);
	}

	void markHead() {
		this.head = true;
	}

	void clearHead() {
		this.head = false;
	}

	String getSourceType() {
		return sourceType;
	}

	String getSourceIdentity() {
		return sourceIdentity;
	}

	String getContentFingerprint() {
		return contentFingerprint;
	}

	Instant getRetrievedAtUtc() {
		return retrievedAtUtc;
	}

	boolean isHead() {
		return head;
	}

	String getCurrentValue() {
		return currentValue;
	}

	boolean isForwardLooking() {
		return forwardLooking;
	}

	Instant getEffectiveUntil() {
		return effectiveUntil;
	}

	String getPreviousValue() {
		return previousValue;
	}

	/** The stored row in the shape the matcher reads. Effective times keep their stored instant. */
	MaritimeSnapshotChange toChange() {
		return new MaritimeSnapshotChange(sourceType, sourceIdentity, contentFingerprint, changeKind, ordering,
				forwardLooking, provenance, sourceEventAt, observedAt, retrievedAtUtc.toString(), retrievedAtUtc,
				sourceMode, freshness, vesselImo, location, effectiveFrom == null ? null : effectiveFrom.toString(),
				effectiveUntil == null ? null : effectiveUntil.toString(), previousValue, currentValue,
				activityCompatibility, locationCompatibility, timeCompatibility, recordedAt);
	}
}
