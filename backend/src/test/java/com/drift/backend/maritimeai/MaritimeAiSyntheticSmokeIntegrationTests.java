package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.maritimecontext.SyntheticMaritimeAiSmokeFixture;
import com.jayway.jsonpath.JsonPath;

/** Exercises the normal authenticated assessment endpoints without an external provider or real evidence. */
@SpringBootTest(properties = { "app.maritime-ai.enabled=true", "app.maritime-context.demo-enabled=false",
		"app.maritime-ai.controlled-replay-stub-enabled=false" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MaritimeAiSyntheticSmokeIntegrationTests.SyntheticSmokeConfiguration.class)
@Transactional
class MaritimeAiSyntheticSmokeIntegrationTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private String token;
	private Long shipmentId;

	@BeforeEach
	void setUp() throws Exception {
		Long companyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, "HARBOURLINE_DEMO");
		String email = "cdg247-smoke-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ? FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", "CDG-247 synthetic smoke user", email, passwords.encode("Example123"), companyId);
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk()).andReturn();
		token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");
		MvcResult created = mvc.perform(post("/api/shipments").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(shipmentJson()))
				.andExpect(status().isCreated()).andReturn();
		shipmentId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
	}

	@Test
	void persistsTheRiskSnapshotUsedForGenerationAfterShipmentRiskChanges() throws Exception {
		String beforeArrival = jdbc.queryForObject("SELECT planned_mother_arrival_at::text FROM shipments WHERE id = ?", String.class, shipmentId);
		String beforeDeparture = jdbc.queryForObject("SELECT planned_feeder_departure_at::text FROM shipments WHERE id = ?", String.class, shipmentId);
		Long beforeRiskTransitions = jdbc.queryForObject("SELECT COUNT(*) FROM shipment_risk_transitions WHERE shipment_id = ?", Long.class, shipmentId);
		Long beforeAisObservations = jdbc.queryForObject("SELECT COUNT(*) FROM vessel_observations", Long.class);

		MvcResult generated = mvc.perform(post("/api/shipments/" + shipmentId + "/maritime-ai-assessments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.state").value("GENERATED"))
				.andExpect(jsonPath("$.validationStatus").value("VALID"))
				.andExpect(jsonPath("$.provider").isNotEmpty())
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.assessed").value(true))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.severity").value("HIGH"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.selectedWindowBasis").value("PLANNED_SHIPMENT_SCHEDULE"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.selectedWindowState").value("AVAILABLE"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.windowSeconds").value(28800))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.estimateFreshness").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.canonicalFactReferences[0]").value("risk:CONNECTION_SEVERITY"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.canonicalFactReferences[1]").value("risk:PLANNED_CONNECTION_WINDOW"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.canonicalFactReferences[2]").value("risk:ARRIVAL_ESTIMATE_NOT_USED"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.canonicalFactReferences[3]").value("risk:ARRIVAL_ESTIMATE_FRESHNESS")).andReturn();
		String generatedJson = generated.getResponse().getContentAsString();
		List<Map<String, Object>> claims = JsonPath.read(generatedJson, "$.interpretation.claims");
		Map<String, Object> riskClaim = claims.stream()
				.filter(claim -> "RISK_BUFFER_REVIEW".equals(claim.get("relationshipType")))
				.findFirst().orElseThrow(() -> new AssertionError("Generated response has no RISK_BUFFER_REVIEW claim"));
		assertThat(riskClaim.get("evidenceIds")).isEqualTo(List.of("synthetic-smoke-001"));
		@SuppressWarnings("unchecked") List<String> riskReferences = (List<String>) riskClaim.get("factReferences");
		assertThat(riskReferences).containsExactlyInAnyOrder("evidence:synthetic-smoke-001",
				"risk:CONNECTION_SEVERITY", "risk:PLANNED_CONNECTION_WINDOW", "risk:ARRIVAL_ESTIMATE_NOT_USED",
				"risk:ARRIVAL_ESTIMATE_FRESHNESS");
		@SuppressWarnings("unchecked") Map<String, Object> riskReview = (Map<String, Object>) riskClaim.get("riskReview");
		assertThat(riskReview).containsEntry("severity", "HIGH")
				.containsEntry("selectedWindowBasis", "PLANNED_SHIPMENT_SCHEDULE")
				.containsEntry("selectedWindowState", "AVAILABLE")
				.containsEntry("estimateFreshness", "UNAVAILABLE");
		assertThat(jdbc.queryForObject("SELECT planned_mother_arrival_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo(beforeArrival);
		assertThat(jdbc.queryForObject("SELECT planned_feeder_departure_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo(beforeDeparture);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_risk_transitions WHERE shipment_id = ?", Long.class, shipmentId))
				.isEqualTo(beforeRiskTransitions);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vessel_observations", Long.class)).isEqualTo(beforeAisObservations);

		jdbc.update("UPDATE shipments SET planned_feeder_departure_at = planned_feeder_departure_at + interval '4 days' WHERE id = ?", shipmentId);
		entityManager.clear();
		mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.risk.level").value("LOW"));

		mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$[0].state").value("GENERATED"))
				.andExpect(jsonPath("$[0].interpretation.claims[0].evidenceIds[0]").value("synthetic-smoke-001"))
				.andExpect(jsonPath("$[0].interpretation.claims[0].riskReview.severity").value("HIGH"))
				.andExpect(jsonPath("$[0].deterministicSummary.connectionRisk.severity").value("HIGH"))
				.andExpect(jsonPath("$[0].deterministicSummary.connectionRisk.selectedWindowBasis").value("PLANNED_SHIPMENT_SCHEDULE"))
				.andExpect(jsonPath("$[0].deterministicSummary.connectionRisk.selectedWindowState").value("AVAILABLE"))
				.andExpect(jsonPath("$[0].deterministicSummary.connectionRisk.windowSeconds").value(28800))
				.andExpect(jsonPath("$[0].deterministicSummary.connectionRisk.estimateFreshness").value("UNAVAILABLE"))
				.andExpect(jsonPath("$[0].deterministicSummary.connectionRisk.calculationLimitations").isArray())
				.andExpect(jsonPath("$[0].deterministicSummary.connectionRisk.canonicalFactReferences[1]").value("risk:PLANNED_CONNECTION_WINDOW"));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE shipment_id = ?", Long.class, shipmentId)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT planned_mother_arrival_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo(beforeArrival);
		assertThat(jdbc.queryForObject("SELECT planned_feeder_departure_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isNotEqualTo(beforeDeparture);
	}

	private static String shipmentJson() {
		return """
				{"shipmentReference":"SYNTHETIC-SMOKE-%s","origin":"Synthetic origin","destination":"Synthetic destination",
				"transshipmentPort":"Synthetic port","motherVessel":"Synthetic vessel","motherVesselImo":"9000001",
				"plannedMotherArrivalAt":"2026-10-01T08:00:00+08:00","feederVessel":"Synthetic feeder",
				"plannedFeederDepartureAt":"2026-10-01T16:00:00+08:00"}
				""".formatted(UUID.randomUUID());
	}

	@TestConfiguration
	static class SyntheticSmokeConfiguration {
		@Bean @Primary MaritimeContextService syntheticSmokeContext() { return SyntheticMaritimeAiSmokeFixture.context(); }
		@Bean @Primary MaritimeAiClient syntheticSmokeClient(
				@Value("${cdg247.synthetic-smoke.use-real-client:false}") boolean useRealClient,
				@Value("${cdg255.synthetic-smoke.capture-interpretation:false}") boolean captureInterpretation,
				@Value("${cdg255.synthetic-smoke.capture-path:}") String capturePath,
				@Value("${app.maritime-ai.provider:}") String provider,
				@Value("${app.maritime-ai.model:}") String model,
				@Value("${app.maritime-ai.api-key:}") String apiKey,
				@Value("${app.maritime-ai.endpoint:}") String endpoint,
				@Value("${app.maritime-ai.external-requests-approved:false}") boolean externalRequestsApproved,
				tools.jackson.databind.ObjectMapper json) {
			MaritimeAiClient delegate;
			if (useRealClient) {
				if (!"APPROVED".equals(System.getenv("CDG247_RUN_REAL_SMOKE"))) {
					throw new IllegalStateException("Real smoke test requires CDG247_RUN_REAL_SMOKE=APPROVED");
				}
				delegate = new ConfiguredMaritimeAiClient(provider, model, apiKey, endpoint, externalRequestsApproved, json);
			} else {
				delegate = evidence -> new MaritimeAiClient.MaritimeAiClientResult("synthetic-stub", "stubbed-responses", "cdg247.synthetic-smoke.v1",
						new MaritimeAiInterpretation("Review the grounded interpretation and limitations.",
								List.of(new MaritimeAiClaim("The documented arrival revision warrants human review alongside the backend HIGH planned connection window; the AIS estimate is unavailable.", List.of("synthetic-smoke-001"), "CONTEXTUAL",
										"MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION", List.of("evidence:synthetic-smoke-001",
											"risk:CONNECTION_SEVERITY", "risk:PLANNED_CONNECTION_WINDOW", "risk:ARRIVAL_ESTIMATE_NOT_USED", "risk:ARRIVAL_ESTIMATE_FRESHNESS"), "RISK_BUFFER_REVIEW",
										new MaritimeAiClaim.RiskReview(com.drift.backend.shipment.ShipmentRisk.Level.HIGH,
											MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE,
											MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE,
											MaritimeAiConnectionRiskContext.EstimateState.UNAVAILABLE))),
								List.of("Synthetic fixture only."), List.of("Have a human compare the current evidence with the stored schedule."), true, null), null);
			}
			return captureInterpretation ? new MaritimeAiSyntheticMultiSourceIntegrationTests.SyntheticInterpretationCaptureClient(delegate, json,
					capturePath(capturePath), Set.of("synthetic-smoke-001"), Set.of("SYNTHETIC_DEMONSTRATION"),
					"cdg255.synthetic-smoke.capture.v2") : delegate;
		}

		static Path capturePath(String configured) {
			if (configured != null && !configured.isBlank()) return Path.of(configured);
			return Path.of(System.getProperty("user.dir"), "target", "cdg255-synthetic-smoke-interpretation-real-response.json");
		}
	}
}
