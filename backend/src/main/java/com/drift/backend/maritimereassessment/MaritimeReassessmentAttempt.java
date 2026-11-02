package com.drift.backend.maritimereassessment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row in the merged maritime_ai_assessments table. A later row does not replace an earlier one. */
@Entity
@Table(name = "maritime_ai_assessments")
class MaritimeReassessmentAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "shipment_id", nullable = false)
	private Long shipmentId;

	@Column(name = "assessment_id", nullable = false, length = 36)
	private String assessmentId;

	@Column(name = "owning_company_id", nullable = false)
	private Long owningCompanyId;

	@Column(name = "bundle_version", nullable = false, length = 100)
	private String bundleVersion;

	@Column(name = "bundle_hash", nullable = false, length = 64)
	private String bundleHash;

	@Column(name = "assessment_cutoff_utc", nullable = false)
	private Instant assessmentCutoffUtc;

	@Column(nullable = false, length = 100)
	private String provider;

	@Column(nullable = false, length = 200)
	private String model;

	@Column(name = "prompt_version", nullable = false, length = 100)
	private String promptVersion;

	@Column(name = "generation_state", nullable = false, length = 16)
	private String generationState;

	@Column(name = "validation_status", nullable = false, length = 32)
	private String validationStatus;

	@Column(name = "output_json")
	private String outputJson;

	@Column(name = "failure_metadata")
	private String failureMetadata;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected MaritimeReassessmentAttempt() {
	}

	static MaritimeReassessmentAttempt create(long shipmentId, long owningCompanyId, String assessmentId,
			String bundleHash, Instant cutoff, String provider, String model, String promptVersion,
			String generationState, String validationStatus, String outputJson, String failureMetadata, Instant createdAt) {
		MaritimeReassessmentAttempt attempt = new MaritimeReassessmentAttempt();
		attempt.shipmentId = shipmentId;
		attempt.owningCompanyId = owningCompanyId;
		attempt.assessmentId = assessmentId;
		attempt.bundleVersion = MaritimeReassessmentBundle.BUNDLE_VERSION;
		attempt.bundleHash = bundleHash;
		attempt.assessmentCutoffUtc = cutoff;
		attempt.provider = provider;
		attempt.model = model;
		attempt.promptVersion = promptVersion;
		attempt.generationState = generationState;
		attempt.validationStatus = validationStatus;
		attempt.outputJson = outputJson;
		attempt.failureMetadata = failureMetadata;
		attempt.createdAt = createdAt;
		return attempt;
	}

	StoredMaritimeReassessment stored() {
		return storedWithId(id);
	}

	StoredMaritimeReassessment storedWithId(long assignedId) {
		return new StoredMaritimeReassessment(assignedId, shipmentId, owningCompanyId, assessmentId, bundleVersion, bundleHash,
				assessmentCutoffUtc, provider, model, promptVersion, generationState, validationStatus, outputJson,
				failureMetadata, createdAt);
	}
}
