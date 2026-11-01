package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimecontext.MaritimeEvidence;

class AuthenticMultiSourceGroundingValidationTest {
	private static final MaritimeEvidence MPA = evidence("MPA_ETA_REVISION", "mpa-authentic");
	private static final MaritimeEvidence NEA = evidence("NEA_WEATHER", "nea-authentic");
	private static final MaritimeAiEvidencePackage INPUT = new MaritimeAiEvidencePackage("cdg246.maritime-evidence-bundle.v1",
			"2026-10-27T16:00:00Z", "UNVERIFIED_IMO_ONLY", List.of(MPA, NEA));

	@Test void acceptsValidTwoSourceUncertainCitationAndRejectsFabricatedOrUnsupportedClaims() {
		assertThat(validate("Both records are retrospective context; shipment association is unverified.",
				List.of("mpa-authentic", "nea-authentic"), "UNCERTAIN")).isNull();
		assertThat(validate("Evidence is available.", List.of("invented"), "UNCERTAIN")).isEqualTo("UNKNOWN_EVIDENCE_ID");
		assertThat(validate("Rainfall caused the ETA revision.", List.of("mpa-authentic", "nea-authentic"), "UNCERTAIN"))
			.isEqualTo("UNSUPPORTED_CAUSAL_CLAIM");
		assertThat(validate("This shipment will be delayed.", List.of("mpa-authentic"), "SHIPMENT_SPECIFIC"))
			.isEqualTo("UNVERIFIED_SHIPMENT_ASSOCIATION");
	}

	@Test void excludesStaleIncompatibleAndUnavailableCitations() {
		var stale = new MaritimeAiEvidencePackage("v1", "2026-10-27T16:00:00Z", "UNVERIFIED_IMO_ONLY",
				List.of(new MaritimeEvidence("NEA_WEATHER", "RETROSPECTIVE_ANALYSIS", "stale", "old", "NEA", "", "manifest", "", "", "", "STALE", "", "", "", "UNKNOWN", "SINGAPORE_PORT_CONTEXT_ONLY", "UNKNOWN", "UNKNOWN", true, true, "UNVERIFIED_IMO_ONLY", "old context")));
		var output = new MaritimeAiInterpretation("x", List.of(new MaritimeAiClaim("Historical record.", List.of("stale"), "UNCERTAIN")), List.of(), List.of(), false, null);
		assertThat(MaritimeAiResponseValidator.validate(output, stale)).isEqualTo("INELIGIBLE_CITED_EVIDENCE");
	}

	private static String validate(String statement, List<String> citations, String applicability) {
		return MaritimeAiResponseValidator.validate(new MaritimeAiInterpretation("x",
				List.of(new MaritimeAiClaim(statement, citations, applicability)), List.of(), List.of(), true, null), INPUT);
	}

	private static MaritimeEvidence evidence(String type, String id) {
		return new MaritimeEvidence(type, "RETROSPECTIVE_ANALYSIS", id, type + " context", "official source", "", "manifest", "2026-10-27T23:25:00+08:00", "2026-10-27T23:25:00+08:00", "2026-10-27T15:33:00Z", "HISTORICAL_REPLAY", "", "context", "", "UNKNOWN", "SINGAPORE_PORT_CONTEXT_ONLY", "COMPATIBLE", "UNKNOWN", true, true, "UNVERIFIED_IMO_ONLY", "No causal or shipment-specific claim.");
	}
}
