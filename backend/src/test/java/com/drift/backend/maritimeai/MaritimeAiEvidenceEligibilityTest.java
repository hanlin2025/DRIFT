package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.drift.backend.maritimecontext.MaritimeEvidence;

class MaritimeAiEvidenceEligibilityTest {
	@Test
	void onlySufficientNonStaleSyntheticDemonstrationEvidenceCanReachTheProvider() {
		MaritimeEvidence synthetic = evidence("SYNTHETIC_DEMONSTRATION", "COMPATIBLE", true, "FRESH");
		MaritimeEvidence replay = evidence("RECORDED_REPLAY", "COMPATIBLE", true, "FRESH");
		MaritimeEvidence live = evidence("LIVE", "COMPATIBLE", true, "APPLICABLE");
		MaritimeEvidence stale = evidence("SYNTHETIC_DEMONSTRATION", "COMPATIBLE", true, "STALE");
		MaritimeEvidence insufficient = evidence("SYNTHETIC_DEMONSTRATION", "COMPATIBLE", false, "FRESH");
		MaritimeEvidence incompatible = evidence("SYNTHETIC_DEMONSTRATION", "INCOMPATIBLE", true, "FRESH");

		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(synthetic, replay, live, stale, insufficient, incompatible)))
				.containsExactly(synthetic);
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(live), true,
				java.util.Set.of("NEA_WEATHER"), java.util.Set.of(live.evidenceId()))).isEmpty();
	}

	@Test
	void authenticRetrospectiveEvidenceRequiresGlobalTypeAndExactRecordApproval() {
		MaritimeEvidence mpa = authenticEvidence("MPA_ETA_REVISION", "mpa-approved", true, "COMPATIBLE");
		MaritimeEvidence nea = new MaritimeEvidence("NEA_WEATHER", "RETROSPECTIVE_ANALYSIS", "nea-approved", "NEA", "NEA", "", "citation",
				null, null, null, "HISTORICAL_REPLAY", "", "", "", "UNKNOWN", "SINGAPORE_PORT_CONTEXT_ONLY", "UNKNOWN",
				"UNKNOWN", true, true, "UNVERIFIED_IMO_ONLY", "Context only");
		MaritimeEvidence unexpected = authenticEvidence("MPA_ETA_REVISION", "mpa-unapproved", true, "COMPATIBLE");
		MaritimeEvidence irrelevant = authenticEvidence("MPA_ETA_REVISION", "mpa-irrelevant", true, "INCOMPATIBLE");
		MaritimeEvidence insufficient = authenticEvidence("MPA_ETA_REVISION", "mpa-insufficient", false, "COMPATIBLE");

		// No organisational approval, or a source type / record approval alone, is not enough.
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(mpa), false,
				java.util.Set.of("MPA_ETA_REVISION"), java.util.Set.of("mpa-approved"))).isEmpty();
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(mpa), true,
				java.util.Set.of(), java.util.Set.of("mpa-approved"))).isEmpty();
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(mpa), true,
				java.util.Set.of("MPA_ETA_REVISION"), java.util.Set.of())).isEmpty();
		// A revoked/mismatched ID and an unexpected companion record cannot leak through.
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(mpa), true,
				java.util.Set.of("MPA_ETA_REVISION"), java.util.Set.of("revoked-mpa-approved"))).isEmpty();
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(mpa, unexpected), true,
				java.util.Set.of("MPA_ETA_REVISION"), java.util.Set.of("mpa-approved"))).containsExactly(mpa);
		assertThat(MaritimeAiAssessmentService.hasUnapprovedAuthenticEvidence(List.of(mpa, unexpected), true,
				java.util.Set.of("MPA_ETA_REVISION"), java.util.Set.of("mpa-approved"))).isTrue();
		assertThat(MaritimeAiAssessmentService.hasUnapprovedAuthenticEvidence(List.of(mpa), false,
				java.util.Set.of("MPA_ETA_REVISION"), java.util.Set.of("mpa-approved"))).isTrue();
		assertThat(MaritimeAiAssessmentService.hasUnapprovedAuthenticEvidence(List.of(mpa), true,
				java.util.Set.of("MPA_ETA_REVISION"), java.util.Set.of("mpa-approved"))).isFalse();
		// Approval never overrides relevance or evidence-sufficiency filters.
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(irrelevant, insufficient), true,
				java.util.Set.of("MPA_ETA_REVISION"), java.util.Set.of("mpa-irrelevant", "mpa-insufficient"))).isEmpty();

		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(mpa, nea), true,
				java.util.Set.of("MPA_ETA_REVISION", "NEA_WEATHER"), java.util.Set.of("nea-approved"))).containsExactly(nea);
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(mpa, nea), true,
				java.util.Set.of("MPA_ETA_REVISION", "NEA_WEATHER"), java.util.Set.of())).isEmpty();
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(mpa, nea), false,
				java.util.Set.of("MPA_ETA_REVISION", "NEA_WEATHER"), java.util.Set.of("nea-approved", mpa.evidenceId()))).isEmpty();
	}

	@Test
	void liveEvidenceRequiresASeparateExactIdAndANewFingerprintNeedsNewApproval() {
		MaritimeEvidence approved = liveEvidence("mpa-eta:mpa:9351048:identity:fingerprint-1");
		MaritimeEvidence revised = liveEvidence("mpa-eta:mpa:9351048:identity:fingerprint-2");
		MaritimeEvidence incompatible = liveEvidence("mpa-eta:mpa:9351048:identity:fingerprint-3", false, "INCOMPATIBLE");
		MaritimeEvidence insufficient = liveEvidence("mpa-eta:mpa:9351048:identity:fingerprint-4", false, "COMPATIBLE");
		MaritimeEvidence synthetic = evidence("SYNTHETIC_DEMONSTRATION", "COMPATIBLE", true, "FRESH");
		java.util.Set<String> approvedIds = java.util.Set.of(approved.evidenceId());

		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(approved), false, java.util.Set.of(),
				java.util.Set.of(), java.util.Set.of())).isEmpty();
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(approved), true,
				java.util.Set.of("MPA_ETA_REVISION"), java.util.Set.of(approved.evidenceId()), java.util.Set.of())).isEmpty();
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(approved, synthetic), false,
				java.util.Set.of(), java.util.Set.of(), approvedIds)).containsExactly(approved, synthetic);
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(approved, revised), false,
				java.util.Set.of(), java.util.Set.of(), approvedIds)).containsExactly(approved);
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(approved), false,
				java.util.Set.of(), java.util.Set.of(), approvedIds)).singleElement()
				.extracting(MaritimeEvidence::previousValue).isEqualTo("arrival=old;departure=old;port=Singapore");
		assertThat(MaritimeAiAssessmentService.hasUnapprovedLiveEvidence(List.of(approved, revised), approvedIds)).isTrue();
		assertThat(MaritimeAiAssessmentService.hasUnapprovedLiveEvidence(List.of(approved), approvedIds)).isFalse();
		assertThat(MaritimeAiAssessmentService.hasUnapprovedLiveEvidence(List.of(incompatible, insufficient), java.util.Set.of())).isFalse();
		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(incompatible, insufficient), false,
				java.util.Set.of(), java.util.Set.of(), java.util.Set.of(incompatible.evidenceId(), insufficient.evidenceId()))).isEmpty();
	}

	private static MaritimeEvidence authenticEvidence(String sourceType, String evidenceId, boolean sufficient, String compatibility) {
		return new MaritimeEvidence(sourceType, "RETROSPECTIVE_ANALYSIS", evidenceId, "Authentic item", "authentic", "", "citation",
				null, null, null, "HISTORICAL_REPLAY", "old", "new", "change", compatibility, compatibility, compatibility,
				compatibility, sufficient, true, "UNVERIFIED_IMO_ONLY", "Historical context only");
	}

	private static MaritimeEvidence liveEvidence(String evidenceId) {
		return liveEvidence(evidenceId, true, "COMPATIBLE");
	}

	private static MaritimeEvidence liveEvidence(String evidenceId, boolean sufficient, String compatibility) {
		return new MaritimeEvidence("MPA_ETA_REVISION", "LIVE", evidenceId, "Persisted MPA expected arrival", "mpa:9351048:identity", "",
				"citation", null, null, "2026-11-11T03:00:00Z", "APPLICABLE", "arrival=old;departure=old;port=Singapore",
				"arrival=new;departure=new;port=Singapore", null, compatibility, compatibility, compatibility, compatibility,
				sufficient, true, "UNVERIFIED_IMO_ONLY", "Persisted head", "9351048", "MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION");
	}

	private static MaritimeEvidence evidence(String mode, String compatibility, boolean sufficient, String freshness) {
		return new MaritimeEvidence("TEST", mode, mode + compatibility + freshness, "Synthetic item", "test", "", "citation",
				null, null, null, freshness, "old", "new", "change", compatibility, compatibility, compatibility,
				compatibility, sufficient, true, "SYNTHETIC_DEMONSTRATION", "test only");
	}
}
