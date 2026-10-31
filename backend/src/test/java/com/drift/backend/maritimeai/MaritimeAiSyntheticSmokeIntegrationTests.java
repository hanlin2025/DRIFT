package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

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
@SpringBootTest(properties = { "app.maritime-ai.enabled=true", "app.maritime-context.demo-enabled=false" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MaritimeAiSyntheticSmokeIntegrationTests.SyntheticSmokeConfiguration.class)
@Transactional
class MaritimeAiSyntheticSmokeIntegrationTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

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
	void generatesPersistsAndReadsASyntheticOnlyAssessmentWithoutChangingShipmentSchedule() throws Exception {
		String beforeArrival = jdbc.queryForObject("SELECT planned_mother_arrival_at::text FROM shipments WHERE id = ?", String.class, shipmentId);
		String beforeDeparture = jdbc.queryForObject("SELECT planned_feeder_departure_at::text FROM shipments WHERE id = ?", String.class, shipmentId);

		mvc.perform(post("/api/shipments/" + shipmentId + "/maritime-ai-assessments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.state").value("GENERATED"))
				.andExpect(jsonPath("$.validationStatus").value("VALID"))
				.andExpect(jsonPath("$.provider").isNotEmpty())
				.andExpect(jsonPath("$.interpretation.claims[0].evidenceIds[0]").value("synthetic-smoke-001"));

		mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$[0].state").value("GENERATED"))
				.andExpect(jsonPath("$[0].interpretation.claims[0].evidenceIds[0]").value("synthetic-smoke-001"));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE shipment_id = ?", Long.class, shipmentId)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT planned_mother_arrival_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo(beforeArrival);
		assertThat(jdbc.queryForObject("SELECT planned_feeder_departure_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo(beforeDeparture);
	}

	@Test
	void persistsTheLongFormalRecommendationRejectionStatus() {
		String rejection = "FORMAL_RECOMMENDATION_NOT_OWNED_BY_CDG247";
		assertThat(rejection.length()).isGreaterThan(32);
		jdbc.update("""
				INSERT INTO maritime_ai_assessments
				(assessment_id, shipment_id, owning_company_id, bundle_version, bundle_hash, assessment_cutoff_utc,
				 provider, model, prompt_version, generation_state, validation_status, output_json, failure_metadata, created_at)
				VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP)
				""", UUID.randomUUID().toString(), shipmentId, 1L, "test", "0".repeat(64), "synthetic-stub",
				"stubbed-responses", "cdg247.synthetic-smoke.v1", "FAILED", rejection, rejection);
		assertThat(jdbc.queryForObject("SELECT validation_status FROM maritime_ai_assessments WHERE shipment_id = ? ORDER BY id DESC LIMIT 1",
				String.class, shipmentId)).isEqualTo(rejection);
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
				@Value("${app.maritime-ai.provider:}") String provider,
				@Value("${app.maritime-ai.model:}") String model,
				@Value("${app.maritime-ai.api-key:}") String apiKey,
				@Value("${app.maritime-ai.endpoint:}") String endpoint,
				tools.jackson.databind.ObjectMapper json) {
			if (useRealClient) {
				if (!"APPROVED".equals(System.getenv("CDG247_RUN_REAL_SMOKE"))) {
					throw new IllegalStateException("Real smoke test requires CDG247_RUN_REAL_SMOKE=APPROVED");
				}
				return new ConfiguredMaritimeAiClient(provider, model, apiKey, endpoint, json);
			}
			return evidence -> new MaritimeAiClient.MaritimeAiClientResult("synthetic-stub", "stubbed-responses", "cdg247.synthetic-smoke.v1",
					new MaritimeAiInterpretation("Synthetic evidence was interpreted.",
							List.of(new MaritimeAiClaim("Synthetic evidence is available for smoke-test validation.", List.of("synthetic-smoke-001"), "UNCERTAIN")),
							List.of("Synthetic fixture only."), List.of(), true, null), null);
		}
	}
}
