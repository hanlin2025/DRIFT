package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.Scenario;
import com.drift.backend.shipment.ControlledScenarioProperties;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Authenticated persistence and exact-context retrieval through PostgreSQL with a local-only client stub. */
@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG289_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG289_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG289_DB_PASSWORD}",
		"spring.flyway.url=${DRIFT_CDG289_JDBC_URL}",
		"spring.flyway.user=${DRIFT_CDG289_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG289_DB_PASSWORD}",
		"spring.flyway.out-of-order=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG289_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG289_OWNERSHIP_TOKEN}",
		"app.ais.enabled=false",
		"app.risk-alerts.activation.command=false",
		"app.risk-alerts.evaluation.enabled=false",
		"app.risk-alerts.mail.enabled=false",
		"app.risk-alerts.mail.dispatch-enabled=false",
		"app.maritime-ai.enabled=true",
		"app.maritime-ai.scenario-enabled=true",
		"app.maritime-ai.external-requests-approved=false",
		"app.maritime-ai.provider=",
		"app.maritime-ai.model=",
		"app.maritime-ai.endpoint=",
		"app.maritime-ai.api-key=",
		"app.maritime-context.demo-enabled=false",
		"app.controlled-scenario.enabled=false",
		"app.controlled-scenario.session-ttl=PT1M",
		"app.jwt.expiration-ms=604800000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = Cdg289ScenarioAssessmentDatabaseGuard.class)
@Import(MaritimeAiScenarioAssessmentPersistenceIntegrationTests.TestAiConfiguration.class)
class MaritimeAiScenarioAssessmentPersistenceIntegrationTests {
	private static final String PASSWORD = "Example123";
	private static final MutableClock TEST_CLOCK = new MutableClock(Instant.parse("2026-11-04T00:00:00Z"));
	private static final AtomicReference<String> RESPONSE_MODE = new AtomicReference<>("VALID");
	private static final AtomicInteger STUB_CALLS = new AtomicInteger();

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired ObjectMapper json;
	@Autowired ControlledScenarioProperties scenarioProperties;

	@BeforeEach void resetTestState() {
		TEST_CLOCK.set(Instant.parse("2026-11-04T00:00:00Z"));
		RESPONSE_MODE.set("VALID");
		STUB_CALLS.set(0);
		scenarioProperties.setEnabled(false);
		scenarioProperties.setDemoCompanyId(null);
		scenarioProperties.setAllowedUserIds(Set.of());
		scenarioProperties.setShipmentAId(null);
		scenarioProperties.setShipmentBId(null);
	}

	@AfterEach void disableScenario() {
		scenarioProperties.setEnabled(false);
		scenarioProperties.setDemoCompanyId(null);
		scenarioProperties.setAllowedUserIds(Set.of());
		scenarioProperties.setShipmentAId(null);
		scenarioProperties.setShipmentBId(null);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void persistsAndRetrievesOnlyTheExactAuthenticatedSessionShipmentAndSnapshot() throws Exception {
		Harness harness = harness();
		UUID sessionId = UUID.randomUUID();
		JsonNode change = startAtChange(harness, sessionId, harness.shipmentAId());
		JsonNode response = generate(harness, sessionId, change, "A", harness.shipmentAId(), "VALID");
		String assessmentId = response.at("/assessment/assessmentId").asText();
		assertThat(response.at("/assessment/state").asText()).isEqualTo("GENERATED");
		assertThat(response.at("/assessment/validationStatus").asText()).isEqualTo("VALID");
		assertThat(response.at("/scenarioShipmentKey").asText()).isEqualTo("A");
		assertThat(response.at("/stage").asText()).isEqualTo("CHANGE");
		assertThat(response.path("evidenceHash").asText()).matches("[0-9a-f]{64}");
		assertThat(response.path("contextHash").asText()).matches("[0-9a-f]{64}");
		assertThat(STUB_CALLS.get()).isEqualTo(1);

		String outputJson = jdbc.queryForObject("SELECT output_json FROM maritime_ai_assessments WHERE assessment_id = ?", String.class, assessmentId);
		JsonNode stored = json.readTree(outputJson);
		assertThat(stored.path("recordType").asText()).isEqualTo(MaritimeAiStoredAssessmentContent.CONTROLLED_SCENARIO_ASSESSMENT);
		assertThat(stored.at("/scenarioContext/sessionId").asText()).isEqualTo(sessionId.toString());
		assertThat(stored.at("/scenarioContext/epochId").asText()).isEqualTo(change.path("epochId").asText());
		assertThat(stored.at("/scenarioContext/revision").asLong()).isEqualTo(change.path("revision").asLong());
		assertThat(stored.at("/scenarioContext/snapshotId").asText()).isEqualTo(change.at("/snapshot/snapshotId").asText());
		assertThat(stored.at("/scenarioContext/simulationCutoffUtc").asText()).isEqualTo(change.at("/snapshot/simulationCutoffUtc").asText());
		assertThat(stored.at("/scenarioContext/scenarioShipmentKey").asText()).isEqualTo("A");
		assertThat(stored.at("/scenarioContext/shipmentId").asLong()).isEqualTo(harness.shipmentAId());
		assertThat(stored.at("/scenarioContext/evidenceHash").asText()).isEqualTo(response.path("evidenceHash").asText());
		assertThat(stored.at("/scenarioContext/contextHash").asText()).isEqualTo(response.path("contextHash").asText());
		assertThat(stored.at("/scenarioContext/managingCompanyId").asLong()).isEqualTo(harness.forwarderCompanyId());
		assertThat(stored.at("/scenarioContext/importerCompanyId").isNull()).isTrue();

		mvc.perform(get(assessmentPath(sessionId, harness.shipmentAId(), assessmentId)).header("Authorization", bearer(harness.managerToken())))
				.andExpect(status().isOk());
		MvcResult wrongShipment = mvc.perform(get(assessmentPath(sessionId, harness.shipmentBId(), assessmentId))
				.header("Authorization", bearer(harness.managerToken()))).andExpect(status().isNotFound()).andReturn();
		assertThat(json.readTree(wrongShipment.getResponse().getContentAsString()).path("code").asText())
				.isEqualTo("SCENARIO_ASSESSMENT_NOT_FOUND");

		UUID otherSession = UUID.randomUUID();
		scenarioCreate(otherSession, harness.managerToken(), harness.shipmentBId());
		mvc.perform(get(assessmentPath(otherSession, harness.shipmentAId(), assessmentId)).header("Authorization", bearer(harness.managerToken())))
				.andExpect(status().isNotFound());
		mvc.perform(get(assessmentPath(sessionId, harness.shipmentAId(), assessmentId)).header("Authorization", bearer(harness.otherTenantToken())))
				.andExpect(status().isForbidden());

		MvcResult ordinaryHistory = mvc.perform(get("/api/shipments/" + harness.shipmentAId() + "/maritime-ai-assessments")
				.header("Authorization", bearer(harness.managerToken()))).andExpect(status().isOk()).andReturn();
		assertThat(json.readTree(ordinaryHistory.getResponse().getContentAsString()).size()).isZero();

		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", harness.otherCompanyId(), harness.shipmentAId());
		mvc.perform(get(assessmentPath(sessionId, harness.shipmentAId(), assessmentId)).header("Authorization", bearer(harness.managerToken())))
				.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE assessment_id = ?", Long.class, assessmentId)).isEqualTo(1L);

		ObjectNode malformedEnvelope = (ObjectNode) stored.deepCopy();
		malformedEnvelope.putNull("recordType");
		malformedEnvelope.putNull("scenarioContext");
		jdbc.update("UPDATE maritime_ai_assessments SET output_json = ? WHERE assessment_id = ?",
				json.writeValueAsString(malformedEnvelope), assessmentId);
		MvcResult malformedOrdinaryHistory = mvc.perform(get("/api/shipments/" + harness.shipmentAId() + "/maritime-ai-assessments")
				.header("Authorization", bearer(harness.managerToken()))).andExpect(status().isOk()).andReturn();
		assertThat(json.readTree(malformedOrdinaryHistory.getResponse().getContentAsString()).size()).isZero();
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void resetAndExpiryHideSavedRecordsWithoutDeletingThem() throws Exception {
		Harness harness = harness();
		UUID sessionId = UUID.randomUUID();
		JsonNode change = startAtChange(harness, sessionId, harness.shipmentAId());
		JsonNode generated = generate(harness, sessionId, change, "A", harness.shipmentAId(), "VALID");
		String assessmentId = generated.at("/assessment/assessmentId").asText();
		JsonNode reset = scenarioCommand(sessionId, harness.managerToken(), "reset", change, "reset-after-ai", 200);
		assertThat(reset.at("/epochId").asText()).isNotEqualTo(change.at("/epochId").asText());
		mvc.perform(get(assessmentPath(sessionId, harness.shipmentAId(), assessmentId)).header("Authorization", bearer(harness.managerToken())))
				.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE assessment_id = ?", Long.class, assessmentId)).isEqualTo(1L);

		UUID expiringSession = UUID.randomUUID();
		JsonNode active = startAtChange(harness, expiringSession, harness.shipmentBId());
		JsonNode second = generate(harness, expiringSession, active, "B", harness.shipmentBId(), "VALID");
		String secondId = second.at("/assessment/assessmentId").asText();
		TEST_CLOCK.set(TEST_CLOCK.instant().plusSeconds(120));
		mvc.perform(get(assessmentPath(expiringSession, harness.shipmentBId(), secondId))
				.header("Authorization", bearer(harness.managerToken()))).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE assessment_id IN (?, ?)",
				Long.class, assessmentId, secondId)).isEqualTo(2L);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void invalidAndAbstainedLaterGenerationsAppendWithoutChangingPriorSuccesses() throws Exception {
		Harness harness = harness();
		UUID failedSession = UUID.randomUUID();
		JsonNode change = startAtChange(harness, failedSession, harness.shipmentAId());
		JsonNode priorSuccess = generate(harness, failedSession, change, "A", harness.shipmentAId(), "VALID");
		JsonNode worsened = scenarioCommand(failedSession, harness.managerToken(), "next", change, "next-invalid", 200);
		JsonNode invalid = generate(harness, failedSession, worsened, "A", harness.shipmentAId(), "INVALID");
		assertThat(invalid.at("/assessment/state").asText()).isEqualTo("FAILED");
		assertThat(invalid.at("/assessment/validationStatus").asText()).isEqualTo("UNSUPPORTED_CARGO_ASSERTION");
		assertThat(invalid.at("/assessment/interpretation").isMissingNode()
				|| invalid.at("/assessment/interpretation").isNull()).isTrue();
		assertThat(jdbc.queryForObject("SELECT generation_state FROM maritime_ai_assessments WHERE assessment_id = ?", String.class,
				priorSuccess.at("/assessment/assessmentId").asText())).isEqualTo("GENERATED");

		UUID abstainSession = UUID.randomUUID();
		JsonNode secondChange = startAtChange(harness, abstainSession, harness.shipmentBId());
		JsonNode secondSuccess = generate(harness, abstainSession, secondChange, "B", harness.shipmentBId(), "VALID");
		JsonNode secondWorsened = scenarioCommand(abstainSession, harness.managerToken(), "next", secondChange, "next-abstain", 200);
		JsonNode abstained = generate(harness, abstainSession, secondWorsened, "B", harness.shipmentBId(), "ABSTAIN");
		assertThat(abstained.at("/assessment/state").asText()).isEqualTo("ABSTAINED");
		assertThat(abstained.at("/assessment/validationStatus").asText()).isEqualTo("INSUFFICIENT_MODEL_EVIDENCE");
		assertThat(abstained.at("/assessment/interpretation").isMissingNode()
				|| abstained.at("/assessment/interpretation").isNull()).isTrue();
		assertThat(jdbc.queryForObject("SELECT generation_state FROM maritime_ai_assessments WHERE assessment_id = ?", String.class,
				secondSuccess.at("/assessment/assessmentId").asText())).isEqualTo("GENERATED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments", Long.class)).isEqualTo(4L);
	}

	private Harness harness() throws Exception {
		Long forwarderCompanyId = company("HARBOURLINE_DEMO");
		Long otherCompanyId = company("STRAITS_FRESH_DEMO");
		String managerEmail = "cdg289-manager-" + UUID.randomUUID() + "@example.com";
		String otherEmail = "cdg289-other-" + UUID.randomUUID() + "@example.com";
		Long managerId = createUser(managerEmail, forwarderCompanyId, "FREIGHT_FORWARDER");
		createUser(otherEmail, otherCompanyId, "IMPORTER");
		String managerToken = login(managerEmail);
		String otherTenantToken = login(otherEmail);
		long shipmentA = createShipment(managerToken, "CDG289-A-" + UUID.randomUUID());
		long shipmentB = createShipment(managerToken, "CDG289-B-" + UUID.randomUUID());
		scenarioProperties.setEnabled(true);
		scenarioProperties.setDemoCompanyId(forwarderCompanyId);
		scenarioProperties.setAllowedUserIds(Set.of(managerId));
		scenarioProperties.setShipmentAId(shipmentA);
		scenarioProperties.setShipmentBId(shipmentB);
		return new Harness(forwarderCompanyId, otherCompanyId, managerId, managerToken, otherTenantToken, shipmentA, shipmentB);
	}

	private long company(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}
	private Long createUser(String email, long companyId, String role) {
		jdbc.update("INSERT INTO users (full_name, email, password_hash, role_id, company_id) SELECT ?, ?, ?, id, ? FROM roles WHERE code = ?",
				"CDG-289 assessment test", email, passwords.encode(PASSWORD), companyId, role);
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}
	private String login(String email) throws Exception {
		String body = json.writeValueAsString(Map.of("email", email, "password", PASSWORD));
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}
	private long createShipment(String token, String reference) throws Exception {
		String body = """
				{"shipmentReference":"%s","origin":"Manila, PH","destination":"Surabaya, ID","transshipmentPort":"Singapore",
				"motherVessel":"CDG289 Synthetic Mother","plannedMotherArrivalAt":"2026-11-04T08:00:00Z",
				"feederVessel":"CDG289 Synthetic Feeder","plannedFeederDepartureAt":"2026-11-05T08:00:00Z"}
				""".formatted(reference);
		MvcResult result = mvc.perform(post("/api/shipments").header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}
	private JsonNode startAtChange(Harness harness, UUID sessionId, long anchorShipmentId) throws Exception {
		JsonNode created = scenarioCreate(sessionId, harness.managerToken(), anchorShipmentId);
		JsonNode baseline = scenarioCommand(sessionId, harness.managerToken(), "start", created, "start-" + UUID.randomUUID(), 200);
		return scenarioCommand(sessionId, harness.managerToken(), "next", baseline, "change-" + UUID.randomUUID(), 200);
	}
	private JsonNode scenarioCreate(UUID sessionId, String token, long anchorId) throws Exception {
		MvcResult result = mvc.perform(put("/api/scenarios/v1/sessions/" + sessionId).header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("anchorShipmentId", anchorId))))
				.andExpect(status().isOk()).andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}
	private JsonNode scenarioCommand(UUID sessionId, String token, String action, JsonNode expected,
			String key, int expectedStatus) throws Exception {
		String body = json.writeValueAsString(Map.of("expectedEpochId", expected.path("epochId").asText(),
				"expectedRevision", expected.path("revision").asLong()));
		MvcResult result = mvc.perform(post("/api/scenarios/v1/sessions/" + sessionId + "/" + action)
				.header("Authorization", bearer(token)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expectedStatus)).andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}
	private JsonNode generate(Harness harness, UUID sessionId, JsonNode state, String key, long shipmentId,
			String responseMode) throws Exception {
		RESPONSE_MODE.set(responseMode);
		JsonNode snapshot = state.path("snapshot");
		Map<String, Object> request = Map.of("scenarioShipmentKey", key, "expectedEpochId", state.path("epochId").asText(),
				"expectedRevision", state.path("revision").asLong(), "expectedSnapshotId", snapshot.path("snapshotId").asText(),
				"expectedSimulationCutoffUtc", snapshot.path("simulationCutoffUtc").asText());
		MvcResult result = mvc.perform(post("/api/scenarios/v1/sessions/" + sessionId + "/shipments/" + shipmentId + "/interpretation")
				.header("Authorization", bearer(harness.managerToken())).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(request))).andExpect(status().isOk()).andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}
	private static String assessmentPath(UUID sessionId, long shipmentId, String assessmentId) {
		return "/api/scenarios/v1/sessions/" + sessionId + "/shipments/" + shipmentId + "/assessments/" + assessmentId;
	}
	private static String bearer(String token) { return "Bearer " + token; }

	private static MaritimeAiInterpretation output(MaritimeAiEvidencePackage input, String mode) {
		if ("INVALID".equals(mode)) {
			MaritimeEvidence evidence = input.evidence().getFirst();
			return new MaritimeAiInterpretation("Review the grounded interpretation and limitations.",
					List.of(new MaritimeAiClaim("The cargo is delayed.", List.of(evidence.evidenceId()), "REGIONAL_CONTEXT")),
					List.of(), List.of(), true, null);
		}
		if ("ABSTAIN".equals(mode)) return new MaritimeAiInterpretation(
				"The synthetic evidence is insufficient for an interpretation.", List.of(), List.of(), List.of(), false, null);
		List<MaritimeAiClaim> claims = new ArrayList<>();
		for (MaritimeEvidence evidence : input.evidence()) {
			if ("MPA_ETA_REVISION".equals(evidence.sourceType())) {
				claims.add(new MaritimeAiClaim("Review the documented schedule change against the shipment plan.",
						List.of(evidence.evidenceId()), "CONTEXTUAL", evidence.vesselRole(), evidence.observationType(),
						evidence.associationScope(), List.of("evidence:" + evidence.evidenceId(),
							"schedule:MOTHER:PLANNED_ARRIVAL", "schedule:FEEDER:PLANNED_DEPARTURE"), "SCHEDULE_REVIEW"));
			} else if ("MPA_OFFICIAL_NOTICE".equals(evidence.sourceType())) {
				claims.add(new MaritimeAiClaim("The fictional port context does not establish an operational impact.",
						List.of(evidence.evidenceId()), "CONTEXTUAL", null, null, "PORT_CONTEXT",
						List.of("evidence:" + evidence.evidenceId()), "PORT_CONTEXT_REVIEW"));
			} else if ("NEA_WEATHER".equals(evidence.sourceType())) {
				claims.add(new MaritimeAiClaim("The fictional regional weather context does not establish an operational impact.",
						List.of(evidence.evidenceId()), "CONTEXTUAL", null, null, "REGIONAL_CONTEXT",
						List.of("evidence:" + evidence.evidenceId()), "REGIONAL_WEATHER_CONTEXT"));
			}
		}
		if (claims.isEmpty()) {
			MaritimeEvidence evidence = input.evidence().getFirst();
			claims.add(new MaritimeAiClaim("Review the synthetic evidence with the operations team.", List.of(evidence.evidenceId()),
					"CONTEXTUAL", evidence.vesselRole(), evidence.observationType(), evidence.associationScope()));
		}
		return new MaritimeAiInterpretation("The documented schedule change warrants cautious human review alongside the fictional context.",
				claims, List.of("The synthetic context does not establish a vessel-specific cause."), List.of(), true, null);
	}

	private record Harness(long forwarderCompanyId, long otherCompanyId, long managerUserId, String managerToken,
			String otherTenantToken, long shipmentAId, long shipmentBId) { }

	@TestConfiguration
	static class TestAiConfiguration {
		private static final Scenario SCENARIO = SyntheticDisruptionScenarioFixture.create("Api");
		@Bean @Primary Clock scenarioClock() { return TEST_CLOCK; }
		@Bean @Primary MaritimeContextService scenarioContext() { return SCENARIO.contextForClock(TEST_CLOCK); }
		@Bean @Primary ConfiguredMaritimeAiClient scenarioStub(ObjectMapper json) {
			return new ConfiguredMaritimeAiClient("offline-json-stub", "cdg289-test", "local-only", "https://stub.invalid",
					true, 20, 800, json, (request, body) -> {
						STUB_CALLS.incrementAndGet();
						String mode = RESPONSE_MODE.getAndSet("VALID");
						MaritimeAiEvidencePackage input = json.readValue(body, MaritimeAiEvidencePackage.class);
						return new ConfiguredMaritimeAiClient.TransportResponse(200, json.writeValueAsString(output(input, mode)));
					});
		}
	}

	private static final class MutableClock extends Clock {
		private final AtomicReference<Instant> current;
		private MutableClock(Instant initial) { current = new AtomicReference<>(initial); }
		void set(Instant value) { current.set(value); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone)) throw new UnsupportedOperationException("UTC only");
			return this;
		}
		@Override public Instant instant() { return current.get(); }
	}
}
