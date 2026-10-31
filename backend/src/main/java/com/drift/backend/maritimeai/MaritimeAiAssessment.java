package com.drift.backend.maritimeai;

import java.time.Instant;

import com.drift.backend.shipment.Shipment;
import jakarta.persistence.*;

/** Persisted generation record, deliberately distinct from a CDG-52 user decision. */
@Entity @Table(name = "maritime_ai_assessments")
public class MaritimeAiAssessment {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "shipment_id", nullable = false) private Shipment shipment;
	@Column(name = "assessment_id", nullable = false, unique = true, length = 36) private String assessmentId;
	@Column(name = "owning_company_id", nullable = false) private Long owningCompanyId;
	@Column(name = "bundle_version", nullable = false, length = 100) private String bundleVersion;
	@Column(name = "bundle_hash", nullable = false, length = 64) private String bundleHash;
	@Column(name = "assessment_cutoff_utc", nullable = false) private Instant assessmentCutoffUtc;
	@Column(name = "provider", nullable = false, length = 100) private String provider;
	@Column(name = "model", nullable = false, length = 200) private String model;
	@Column(name = "prompt_version", nullable = false, length = 100) private String promptVersion;
	@Enumerated(EnumType.STRING) @Column(name = "generation_state", nullable = false, length = 16) private MaritimeAiGenerationState generationState;
	@Column(name = "validation_status", nullable = false, length = 64) private String validationStatus;
	// V28 deliberately uses PostgreSQL TEXT, not an OID large object.
	@Column(name = "output_json", columnDefinition = "text") private String outputJson;
	@Column(name = "failure_metadata", columnDefinition = "text") private String failureMetadata;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	protected MaritimeAiAssessment() { }
	MaritimeAiAssessment(Shipment shipment, String assessmentId, String bundleVersion, String bundleHash, Instant cutoff,
			String provider, String model, String promptVersion, MaritimeAiGenerationState state, String validation,
			String outputJson, String failure, Instant createdAt) {
		this.shipment = shipment; this.assessmentId = assessmentId; this.owningCompanyId = shipment.getCompany().getId();
		this.bundleVersion = bundleVersion; this.bundleHash = bundleHash; this.assessmentCutoffUtc = cutoff;
		this.provider = provider; this.model = model; this.promptVersion = promptVersion; this.generationState = state;
		this.validationStatus = validation; this.outputJson = outputJson; this.failureMetadata = failure; this.createdAt = createdAt;
	}
	public String getAssessmentId() { return assessmentId; } public MaritimeAiGenerationState getGenerationState() { return generationState; }
	public String getValidationStatus() { return validationStatus; } public String getOutputJson() { return outputJson; }
	public String getFailureMetadata() { return failureMetadata; } public String getBundleVersion() { return bundleVersion; }
	public String getBundleHash() { return bundleHash; } public Instant getAssessmentCutoffUtc() { return assessmentCutoffUtc; }
	public String getProvider() { return provider; } public String getModel() { return model; } public String getPromptVersion() { return promptVersion; }
	public Instant getCreatedAt() { return createdAt; }
}
