package com.drift.backend.maritimereassessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.drift.backend.maritimecontext.MaritimeEvidence;

/** The compatible evidence that identifies one bundle. Retrieval time is not part of the hash. */
final class MaritimeReassessmentBundle {

	static final String BUNDLE_VERSION = "cdg246.maritime-evidence-bundle.v1";
	private static final String VERIFIED = "VERIFIED_ASSOCIATION";

	private final String datasetId;
	private final String hash;
	private final List<String> snapshotIds;
	private final List<String> excludedSnapshotIds;
	private final String sourceFreshness;
	private final boolean forwardLooking;
	private final String associationCeiling;
	private final List<MaritimeEvidence> included;

	private MaritimeReassessmentBundle(String datasetId, String hash, List<String> snapshotIds,
			List<String> excludedSnapshotIds, String sourceFreshness, boolean forwardLooking,
			String associationCeiling, List<MaritimeEvidence> included) {
		this.datasetId = datasetId;
		this.hash = hash;
		this.snapshotIds = snapshotIds;
		this.excludedSnapshotIds = excludedSnapshotIds;
		this.sourceFreshness = sourceFreshness;
		this.forwardLooking = forwardLooking;
		this.associationCeiling = associationCeiling;
		this.included = included;
	}

	static MaritimeReassessmentBundle normalize(MaritimeEvidenceBundle bundle) {
		if (bundle == null) {
			throw new IllegalArgumentException("Evidence bundle is required");
		}
		if (bundle.datasetId() == null || bundle.datasetId().isBlank()) {
			throw new IllegalArgumentException("Dataset id is required");
		}
		if (bundle.cutoff() == null) {
			throw new IllegalArgumentException("Assessment cutoff is required");
		}
		if (bundle.evidence() == null) {
			throw new IllegalArgumentException("Evidence is required");
		}
		List<MaritimeEvidence> included = new ArrayList<>();
		List<String> excluded = new ArrayList<>();
		for (MaritimeEvidence item : bundle.evidence()) {
			if (item == null) {
				throw new IllegalArgumentException("Evidence item is required");
			}
			if (item.evidenceId() == null || item.evidenceId().isBlank()) {
				throw new IllegalArgumentException("Evidence id is required");
			}
			if (item.sourceType() == null || item.sourceType().isBlank()) {
				throw new IllegalArgumentException("Evidence source type is required");
			}
			if ("INCOMPATIBLE".equals(item.compatibilityState())) {
				excluded.add(item.evidenceId());
			} else {
				included.add(item);
			}
		}
		included.sort(Comparator.comparing(MaritimeEvidence::sourceType).thenComparing(MaritimeEvidence::evidenceId)
				.thenComparing(item -> text(item.currentValue())));
		List<String> snapshots = snapshots(bundle.snapshotIds(), included);
		String freshness = freshness(included);
		return new MaritimeReassessmentBundle(bundle.datasetId().trim(), hash(bundle.datasetId().trim(), snapshots, included),
				snapshots, List.copyOf(excluded), freshness, forwardLooking(included), ceiling(included), List.copyOf(included));
	}

	private static List<String> snapshots(List<String> supplied, List<MaritimeEvidence> included) {
		List<String> ids = new ArrayList<>();
		if (supplied != null && !supplied.isEmpty()) {
			for (String id : supplied) {
				if (id != null && !id.isBlank()) {
					ids.add(id.trim());
				}
			}
		}
		if (ids.isEmpty()) {
			for (MaritimeEvidence item : included) {
				ids.add(item.evidenceId());
			}
		}
		return ids.stream().distinct().sorted().toList();
	}

	private static String freshness(List<MaritimeEvidence> included) {
		List<String> values = included.stream().map(item -> text(item.freshness())).filter(value -> !value.isEmpty())
				.distinct().sorted().toList();
		return values.isEmpty() ? "UNKNOWN" : String.join(",", values);
	}

	private static boolean forwardLooking(List<MaritimeEvidence> included) {
		return !included.isEmpty() && included.stream().allMatch(item -> "LIVE".equals(item.sourceMode())
				&& "APPLICABLE".equals(item.freshness()));
	}

	private static String ceiling(List<MaritimeEvidence> included) {
		if (included.isEmpty()) {
			return "UNAVAILABLE";
		}
		if (included.stream().allMatch(item -> VERIFIED.equals(item.shipmentAssociationConfidence()))) {
			return VERIFIED;
		}
		if (included.stream().anyMatch(item -> "UNVERIFIED_IMO_ONLY".equals(item.shipmentAssociationConfidence()))) {
			return "UNVERIFIED_IMO_ONLY";
		}
		return "UNAVAILABLE";
	}

	private static String hash(String datasetId, List<String> snapshots, List<MaritimeEvidence> included) {
		StringBuilder body = new StringBuilder();
		body.append(datasetId).append('\n');
		for (String snapshot : snapshots) {
			body.append(snapshot).append('\n');
		}
		for (MaritimeEvidence item : included) {
			body.append("---\n");
			body.append(text(item.sourceType())).append('\n');
			body.append(text(item.evidenceId())).append('\n');
			body.append(text(item.sourceMode())).append('\n');
			body.append(text(item.freshness())).append('\n');
			body.append(text(item.previousValue())).append('\n');
			body.append(text(item.currentValue())).append('\n');
			body.append(text(item.changeMagnitude())).append('\n');
			body.append(text(item.activityCompatibility())).append('\n');
			body.append(text(item.locationCompatibility())).append('\n');
			body.append(text(item.timeCompatibility())).append('\n');
			body.append(text(item.compatibilityState())).append('\n');
			body.append(text(item.shipmentAssociationConfidence())).append('\n');
			body.append(item.evidenceSufficient()).append('\n');
			body.append(item.humanReviewRequired()).append('\n');
			body.append(text(item.limitation())).append('\n');
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required", ex);
		}
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}

	String datasetId() { return datasetId; }
	String hash() { return hash; }
	List<String> snapshotIds() { return snapshotIds; }
	List<String> excludedSnapshotIds() { return excludedSnapshotIds; }
	String sourceFreshness() { return sourceFreshness; }
	boolean forwardLooking() { return forwardLooking; }
	String associationCeiling() { return associationCeiling; }
	List<MaritimeEvidence> included() { return included; }
	boolean hasCompatibleEvidence() { return !included.isEmpty(); }

	MaritimeProviderRequest providerRequest(java.time.Duration timeout) {
		return new MaritimeProviderRequest(datasetId, snapshotIds, associationCeiling, included.stream()
				.map(item -> new MaritimeProviderRequest.MaritimeProviderEvidence(
						item.sourceType(), item.sourceMode(), item.evidenceId(), item.freshness(),
						item.previousValue(), item.currentValue(), item.activityCompatibility(),
						item.locationCompatibility(), item.timeCompatibility(), item.compatibilityState(),
						item.shipmentAssociationConfidence(), item.limitation()))
				.toList(), timeout);
	}
}
