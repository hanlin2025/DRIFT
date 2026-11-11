package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture;

/**
 * Replays owner-only parsed model assessments. No provider client or application endpoint is invoked.
 * Files are named A-CHANGE-001.json, B-WORSENED-001.json, etc. and contain only the
 * parsed {"assessment":...} object, without headers, credentials, or provider metadata.
 */
class ScenarioMaritimeAiPassRateEvaluationTest {
	@Test void reportCapturedRealResponseValidationByShipmentAndStage() throws Exception {
		String configured = System.getProperty("scenarioCaptureDir");
		Assumptions.assumeTrue(configured != null && !configured.isBlank(),
				"Set -DscenarioCaptureDir to an owner-only directory of parsed synthetic assessments");
		Path directory = Path.of(configured);
		assertThat(directory).isDirectory();
		var fixture = SyntheticDisruptionScenarioFixture.create("PassRateReplay");
		Map<String, Integer> validByCase = new LinkedHashMap<>();
		Map<String, Integer> totalByCase = new LinkedHashMap<>();
		Map<String, Integer> reasons = new LinkedHashMap<>();
		int total = 0;
		int valid = 0;
		for (int shipment = 0; shipment < 2; shipment++) for (int stage = 1; stage <= 2; stage++) {
			String key = (shipment == 0 ? "A" : "B") + "-" + (stage == 1 ? "CHANGE" : "WORSENED");
			List<Path> captures = new ArrayList<>();
			try (var paths = Files.list(directory)) {
				paths.filter(path -> path.getFileName().toString().matches(key + "-[0-9]{3}\\.json"))
						.sorted().forEach(captures::add);
			}
			assertThat(captures).as("missing real captures for " + key).isNotEmpty();
			int successes = 0;
			for (Path capture : captures) {
				var result = MaritimeAiResponseValidator.validateScenarioV16(Files.readString(capture),
						ScenarioQualityStubClientTest.input(fixture, stage, shipment));
				if (result.valid()) successes++;
				else reasons.merge(result.validationStatus() + "@" + result.fieldPath(), 1, Integer::sum);
			}
			validByCase.put(key, successes);
			totalByCase.put(key, captures.size());
			total += captures.size();
			valid += successes;
		}
		System.out.println("SCENARIO_REAL_CAPTURE_VALIDATION valid=" + validByCase + " total="
				+ totalByCase + " overall=" + valid + "/" + total + " reasons=" + reasons);
		assertThat(total).isGreaterThan(0);
	}
}
