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
		MaritimeEvidence stale = evidence("SYNTHETIC_DEMONSTRATION", "COMPATIBLE", true, "STALE");
		MaritimeEvidence insufficient = evidence("SYNTHETIC_DEMONSTRATION", "COMPATIBLE", false, "FRESH");
		MaritimeEvidence incompatible = evidence("SYNTHETIC_DEMONSTRATION", "INCOMPATIBLE", true, "FRESH");

		assertThat(MaritimeAiAssessmentService.eligibleForInference(List.of(synthetic, replay, stale, insufficient, incompatible)))
				.containsExactly(synthetic);
	}

	private static MaritimeEvidence evidence(String mode, String compatibility, boolean sufficient, String freshness) {
		return new MaritimeEvidence("TEST", mode, mode + compatibility + freshness, "Synthetic item", "test", "", "citation",
				null, null, null, freshness, "old", "new", "change", compatibility, compatibility, compatibility,
				compatibility, sufficient, true, "SYNTHETIC_DEMONSTRATION", "test only");
	}
}
