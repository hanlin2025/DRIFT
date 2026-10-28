package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.drift.backend.maritimecontext.MaritimeEvidence;

class MaritimeAiResponseValidatorTest {
	private static final MaritimeEvidence MPA = new MaritimeEvidence("MPA_ETA_REVISION", "RECORDED_REPLAY", "mpa-1", "ETA revision", "MPA", "", "manifest", "", "", "2026-10-26T15:00:00Z", "FRESH", "old", "new", "+180 minutes", "UNKNOWN", "UNKNOWN", "COMPATIBLE", "UNKNOWN", true, true, "UNVERIFIED_IMO_ONLY", "Replay");
	private static final MaritimeEvidence WEATHER = new MaritimeEvidence("NEA_WEATHER", "UNAVAILABLE", "weather-1", "Weather", "NEA", "", "", null, null, null, "UNAVAILABLE", "", "", "", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", false, true, "UNAVAILABLE", "Unavailable");

	@Test void acceptsCitedNonCausalEvidence() {
		assertThat(validate(new MaritimeAiInterpretation("ETA changed", List.of(new MaritimeAiClaim("The recorded expected arrival changed by 180 minutes.", List.of("mpa-1"), "UNCERTAIN")), List.of(), List.of(), true, null))).isNull();
	}
	@Test void rejectsUnknownCitationCausalClaimAndUnavailableWeather() {
		assertThat(validate(new MaritimeAiInterpretation("x", List.of(new MaritimeAiClaim("x", List.of("other"), "x")), List.of(), List.of(), false, null))).isEqualTo("UNKNOWN_EVIDENCE_ID");
		assertThat(validate(new MaritimeAiInterpretation("x", List.of(new MaritimeAiClaim("Weather caused the ETA revision.", List.of("mpa-1"), "x")), List.of(), List.of(), false, null))).isEqualTo("UNSUPPORTED_CAUSAL_CLAIM");
		assertThat(validate(new MaritimeAiInterpretation("x", List.of(new MaritimeAiClaim("Weather is known.", List.of("weather-1"), "x")), List.of(), List.of(), false, null))).isEqualTo("INELIGIBLE_CITED_EVIDENCE");
	}
	private static String validate(MaritimeAiInterpretation output) { return MaritimeAiResponseValidator.validate(output, new MaritimeAiEvidencePackage("v1", "2026-10-28T00:00:00Z", "UNVERIFIED_IMO_ONLY", List.of(MPA, WEATHER))); }
}
