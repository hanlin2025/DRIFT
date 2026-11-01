package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.maritimecontext.SharedEvidenceShipmentPairFixture;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Offline end-to-end evaluation through shipment authorization, risk, evidence, validation, and persistence. */
@SpringBootTest(properties = { "app.maritime-ai.enabled=true", "app.maritime-context.demo-enabled=false",
		"app.ais.enabled=false", "app.maritime-ai.controlled-replay-stub-enabled=false" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MaritimeAiCrossShipmentEvaluationIntegrationTests.CrossShipmentConfiguration.class)
@Transactional
class MaritimeAiCrossShipmentEvaluationIntegrationTests {
	private static final String PORT = "Synthetic Multi-source Port";
	private static final String MOTHER = "Synthetic Multi-source Vessel";
	private static final String FEEDER = "Synthetic Multi-source Feeder";
	private static final String MPA_ID = "synthetic-mpa-schedule-001";
	private static final String NEA_ID = "synthetic-nea-rainfall-001";
	private static final String MPA_CITATION = "Synthetic MPA-style revision record; source string and retrieval record are fictional.";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;
	@Autowired ObjectMapper json;
	@Autowired ContextDerivedClient client;

	private Long managerCompanyId;
	private Long managerUserId;
	private Long highShipmentId;
	private Long lowShipmentId;
	private String managerToken;
	private String currentImporterToken;
	private String unrelatedToken;
	private Long currentImporterCompanyId;
	private Long successorCompanyId;

	@BeforeEach
	void setUp() throws Exception {
		client.clear();
		managerCompanyId = companyId("HARBOURLINE_DEMO");
		currentImporterCompanyId = companyId("STRAITS_FRESH_DEMO");
		successorCompanyId = createCompany("CDG262_SUCCESSOR_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
		Long unrelatedCompanyId = createCompany("CDG262_UNRELATED_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));

		String managerEmail = createAccount(managerCompanyId, "FREIGHT_FORWARDER", "manager");
		managerUserId = userId(managerEmail);
		managerToken = login(managerEmail);
		currentImporterToken = login(createAccount(currentImporterCompanyId, "IMPORTER", "current-importer"));
		unrelatedToken = login(createAccount(unrelatedCompanyId, "IMPORTER", "unrelated-importer"));

		highShipmentId = createShipment("CDG262-HIGH", "2026-10-01T05:00:00+08:00", "2026-10-01T14:00:00+08:00");
		lowShipmentId = createShipment("CDG262-LOW", "2026-10-01T06:00:00+08:00", "2026-10-02T12:00:00+08:00");
	}

	@Test
	void sameEligibleEvidenceProducesContextDerivedGroundedInterpretationsForActualDifferentRisksWithoutOperationalMutation() throws Exception {
		JsonNode highBefore = shipmentDetail(highShipmentId, managerToken);
		JsonNode lowBefore = shipmentDetail(lowShipmentId, managerToken);
		assertThat(highBefore.path("risk").path("level").asText()).isEqualTo("HIGH");
		assertThat(lowBefore.path("risk").path("level").asText()).isEqualTo("LOW");
		assertThat(highBefore.path("arrivalComparison").path("unavailableReason").path("code").asText()).isEqualTo("NO_POSITION");
		assertThat(lowBefore.path("arrivalComparison").path("unavailableReason").path("code").asText()).isEqualTo("NO_POSITION");
		assertThat(highBefore.path("recommendation").path("available").asBoolean()).isFalse();
		assertThat(lowBefore.path("recommendation").path("available").asBoolean()).isFalse();

		String operationalStateBefore = operationalSnapshot(highShipmentId, lowShipmentId);
		MvcResult highResult = generate(highShipmentId, managerToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("GENERATED"))
				.andExpect(jsonPath("$.validationStatus").value("VALID"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.severity").value("HIGH"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.selectedWindowBasis").value("PLANNED_SHIPMENT_SCHEDULE"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.windowSeconds").value(32_400))
				.andReturn();
		MvcResult lowResult = generate(lowShipmentId, managerToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("GENERATED"))
				.andExpect(jsonPath("$.validationStatus").value("VALID"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.severity").value("LOW"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.selectedWindowBasis").value("PLANNED_SHIPMENT_SCHEDULE"))
				.andExpect(jsonPath("$.deterministicSummary.connectionRisk.windowSeconds").value(108_000))
				.andReturn();

		assertThat(client.inputs()).hasSize(2);
		MaritimeAiEvidencePackage highInput = client.inputs().get(0);
		MaritimeAiEvidencePackage lowInput = client.inputs().get(1);
		assertThat(highInput.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
		assertThat(lowInput.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
		assertThat(highInput.evidence()).containsExactlyElementsOf(lowInput.evidence());
		assertThat(highInput.evidence()).extracting(MaritimeEvidence::evidenceId).containsExactly(MPA_ID, NEA_ID);
		assertThat(highInput.evidence()).extracting(MaritimeEvidence::citation).containsExactly(
				MPA_CITATION, "Synthetic NEA-style rainfall observation for the Synthetic Port Area; regional context only.");
		assertThat(highInput.evidence()).extracting(MaritimeEvidence::observedAt)
				.containsExactly("2026-10-01T06:00:00+08:00", "2026-10-01T05:00:00+08:00");
		assertThat(highInput.evidence()).extracting(MaritimeEvidence::currentValue)
				.containsExactly("2026-10-01T06:00:00+08:00", "Synthetic rainfall: 12.0 mm; station/area: Synthetic Port Area.");
		assertThat(highInput.shipmentContext().connectionRisk().assessed()).isTrue();
		assertThat(lowInput.shipmentContext().connectionRisk().assessed()).isTrue();
		assertThat(highInput.shipmentContext().connectionRisk().riskLevel()).isEqualTo(com.drift.backend.shipment.ShipmentRisk.Level.HIGH);
		assertThat(lowInput.shipmentContext().connectionRisk().riskLevel()).isEqualTo(com.drift.backend.shipment.ShipmentRisk.Level.LOW);
		assertThat(highInput.shipmentContext().connectionRisk().plannedWindow().durationSeconds()).isEqualTo(32_400L);
		assertThat(lowInput.shipmentContext().connectionRisk().plannedWindow().durationSeconds()).isEqualTo(108_000L);
		assertThat(highInput.shipmentContext().connectionRisk().riskBasis()).isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE);
		assertThat(lowInput.shipmentContext().connectionRisk().riskBasis()).isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE);
		assertThat(highInput.shipmentContext().connectionRisk().arrivalEstimate().state())
				.isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.UNAVAILABLE);
		assertThat(lowInput.shipmentContext().connectionRisk().arrivalEstimate().state())
				.isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.UNAVAILABLE);
		assertThat(MaritimeAiCanonicalFactReferences.allowed(highInput)).contains("evidence:" + MPA_ID, "evidence:" + NEA_ID,
				"risk:CONNECTION_SEVERITY", "risk:PLANNED_CONNECTION_WINDOW", "risk:ARRIVAL_ESTIMATE_NOT_USED", "risk:ARRIVAL_ESTIMATE_FRESHNESS");
		assertThat(MaritimeAiCanonicalFactReferences.allowed(lowInput)).isEqualTo(MaritimeAiCanonicalFactReferences.allowed(highInput));

		JsonNode highAssessment = json.readTree(highResult.getResponse().getContentAsString());
		JsonNode lowAssessment = json.readTree(lowResult.getResponse().getContentAsString());
		assertThat(highAssessment.at("/interpretation/claims/0/statement").asText()).contains("HIGH", "planned connection window");
		assertThat(lowAssessment.at("/interpretation/claims/0/statement").asText()).contains("LOW", "planned connection window");
		assertThat(highAssessment.at("/interpretation/claims/0/riskReview/severity").asText()).isEqualTo("HIGH");
		assertThat(lowAssessment.at("/interpretation/claims/0/riskReview/severity").asText()).isEqualTo("LOW");
		assertThat(highAssessment.at("/interpretation/claims/0/factReferences").toString())
				.isEqualTo(lowAssessment.at("/interpretation/claims/0/factReferences").toString());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE shipment_id IN (?, ?)", Long.class,
				highShipmentId, lowShipmentId)).isEqualTo(2L);

		assessmentHistory(highShipmentId, managerToken).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].deterministicSummary.connectionRisk.severity").value("HIGH"))
				.andExpect(jsonPath("$[0].deterministicSummary.evidenceFacts[0].evidenceId").value(MPA_ID));
		assessmentHistory(lowShipmentId, managerToken).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].deterministicSummary.connectionRisk.severity").value("LOW"))
				.andExpect(jsonPath("$[0].deterministicSummary.evidenceFacts[0].evidenceId").value(MPA_ID));
		assertThat(operationalSnapshot(highShipmentId, lowShipmentId)).isEqualTo(operationalStateBefore);

		assertDetailOperationalFactsUnchanged(highShipmentId, managerToken, highBefore);
		assertDetailOperationalFactsUnchanged(lowShipmentId, managerToken, lowBefore);
	}

	@Test
	void linkedImporterCanReadAndGenerateButFormerAndUnrelatedCompaniesCannotRetrieveOrGenerateOrInvokeTheClient() throws Exception {
		linkImporter(highShipmentId, currentImporterCompanyId);
		createRelationship(managerCompanyId, currentImporterCompanyId, managerUserId);
		entityManager.clear();

		shipmentDetail(highShipmentId, currentImporterToken);
		assessmentHistory(highShipmentId, currentImporterToken).andExpect(status().isOk());
		generate(highShipmentId, currentImporterToken).andExpect(status().isOk()).andExpect(jsonPath("$.validationStatus").value("VALID"));
		assertThat(client.inputs()).hasSize(1);

		createRelationship(managerCompanyId, successorCompanyId, managerUserId);
		linkImporter(highShipmentId, successorCompanyId);
		entityManager.clear();
		MvcResult formerRead = assessmentHistory(highShipmentId, currentImporterToken).andExpect(status().isForbidden()).andReturn();
		MvcResult formerGenerate = generate(highShipmentId, currentImporterToken).andExpect(status().isForbidden()).andReturn();
		MvcResult unrelatedRead = assessmentHistory(lowShipmentId, unrelatedToken).andExpect(status().isForbidden()).andReturn();
		MvcResult unrelatedGenerate = generate(lowShipmentId, unrelatedToken).andExpect(status().isForbidden()).andReturn();
		assertThat(client.inputs()).hasSize(1);
		for (MvcResult denied : List.of(formerRead, formerGenerate, unrelatedRead, unrelatedGenerate)) {
			assertThat(denied.getResponse().getContentAsString())
					.doesNotContain("HIGH", "LOW", "CRITICAL", "risk:CONNECTION_SEVERITY", MPA_ID, NEA_ID);
		}
	}

	private org.springframework.test.web.servlet.ResultActions generate(Long shipmentId, String token) throws Exception {
		return mvc.perform(post("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
				.header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions assessmentHistory(Long shipmentId, String token) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
				.header("Authorization", "Bearer " + token));
	}

	private JsonNode shipmentDetail(Long shipmentId, String token) throws Exception {
		MvcResult result = mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private void assertDetailOperationalFactsUnchanged(Long shipmentId, String token, JsonNode before) throws Exception {
		JsonNode after = shipmentDetail(shipmentId, token);
		assertThat(after.path("risk")).isEqualTo(before.path("risk"));
		assertThat(after.path("recommendation")).isEqualTo(before.path("recommendation"));
		assertThat(after.path("motherOperationalState")).isEqualTo(before.path("motherOperationalState"));
		assertThat(after.path("feederOperationalState")).isEqualTo(before.path("feederOperationalState"));
	}

	private String operationalSnapshot(Long firstId, Long secondId) {
		Object[] ids = { firstId, secondId };
		return rows("SELECT to_jsonb(s)::text FROM shipments s WHERE id IN (?, ?) ORDER BY id", ids)
				+ rows("SELECT to_jsonb(s)::text FROM shipment_risk_states s WHERE shipment_id IN (?, ?) ORDER BY shipment_id", ids)
				+ rows("SELECT to_jsonb(t)::text FROM shipment_risk_transitions t WHERE shipment_id IN (?, ?) ORDER BY id", ids)
				+ rows("SELECT to_jsonb(d)::text FROM shipment_risk_alert_deliveries d JOIN shipment_risk_transitions t ON t.id = d.transition_id WHERE t.shipment_id IN (?, ?) ORDER BY d.id", ids)
				+ rows("SELECT to_jsonb(e)::text FROM shipment_change_events e WHERE shipment_id IN (?, ?) ORDER BY id", ids)
				+ rows("SELECT to_jsonb(f)::text FROM shipment_field_changes f JOIN shipment_change_events e ON e.id = f.event_id WHERE e.shipment_id IN (?, ?) ORDER BY f.id", ids)
				+ rows("SELECT to_jsonb(a)::text FROM shipment_risk_alert_activation a ORDER BY id", new Object[0])
				+ rows("SELECT to_jsonb(v)::text FROM vessel_observations v WHERE lower(vessel_name) IN (lower(?), lower(?)) ORDER BY id", MOTHER, FEEDER);
	}

	private String rows(String sql, Object... arguments) {
		return jdbc.queryForList(sql, String.class, arguments).toString() + "\n";
	}

	private Long createShipment(String reference, String motherArrival, String feederDeparture) throws Exception {
		String body = """
				{"shipmentReference":"%s","origin":"Synthetic origin","destination":"Synthetic destination",
				"transshipmentPort":"%s","motherVessel":"%s","motherVesselImo":"9000002",
				"plannedMotherArrivalAt":"%s","feederVessel":"%s","plannedFeederDepartureAt":"%s"}
				""".formatted(reference, PORT, MOTHER, motherArrival, FEEDER, feederDeparture);
		MvcResult response = mvc.perform(post("/api/shipments").header("Authorization", "Bearer " + managerToken)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
		return ((Number) JsonPath.read(response.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private String createAccount(Long companyId, String role, String tag) {
		String email = "cdg262-" + tag + "-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ? FROM roles WHERE code = ?
				""", "CDG-262 " + tag, email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private String login(String email) throws Exception {
		MvcResult response = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(response.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany(String code) {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-262 " + code);
		return companyId(code);
	}

	private Long userId(String email) {
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	private void createRelationship(Long forwarderId, Long importerId, Long actorId) {
		jdbc.update("""
				INSERT INTO forwarder_importer_relationships
				(forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
				VALUES (?, ?, TRUE, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
				""", forwarderId, importerId, actorId, actorId);
	}

	private void linkImporter(Long shipmentId, Long importerCompanyId) {
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importerCompanyId, shipmentId);
	}

	@TestConfiguration
	static class CrossShipmentConfiguration {
		@Bean @Primary MaritimeContextService sharedEvidenceContext() { return SharedEvidenceShipmentPairFixture.context(); }
		@Bean @Primary ContextDerivedClient contextDerivedClient() { return new ContextDerivedClient(); }
	}

	static final class ContextDerivedClient implements MaritimeAiClient {
		private final List<MaritimeAiEvidencePackage> inputs = new ArrayList<>();

		@Override
		public MaritimeAiClientResult interpret(MaritimeAiEvidencePackage input) {
			inputs.add(input);
			MaritimeAiConnectionRiskContext risk = input.shipmentContext().connectionRisk();
			MaritimeEvidence mpa = input.evidence().stream().filter(item -> MPA_ID.equals(item.evidenceId())).findFirst().orElseThrow();
			MaritimeEvidence nea = input.evidence().stream().filter(item -> NEA_ID.equals(item.evidenceId())).findFirst().orElseThrow();
			List<String> riskRefs = new ArrayList<>(MaritimeAiCanonicalFactReferences.riskReferences(input));
			List<String> mpaRefs = new ArrayList<>(riskRefs);
			mpaRefs.add("evidence:" + mpa.evidenceId());
			String statement = "The documented MPA arrival revision warrants review alongside the backend " + risk.riskLevel().name()
					+ " planned connection window; the AIS estimate is unavailable.";
			MaritimeAiClaim riskClaim = new MaritimeAiClaim(statement, List.of(mpa.evidenceId()), "CONTEXTUAL", mpa.vesselRole(),
				mpa.observationType(), mpa.associationScope(), mpaRefs, "RISK_BUFFER_REVIEW", reviewFor(risk));
			MaritimeAiClaim weatherClaim = new MaritimeAiClaim("The synthetic rainfall observation provides regional context only.",
				List.of(nea.evidenceId()), "CONTEXTUAL", null, null, nea.associationScope(),
				List.of("evidence:" + nea.evidenceId()), "REGIONAL_WEATHER_CONTEXT");
			MaritimeAiInterpretation interpretation = new MaritimeAiInterpretation(
				"The maritime observations remain separate from the backend connection calculation.", List.of(riskClaim, weatherClaim),
				List.of("The source records do not establish a delay or missed connection."),
				List.of("Verify the carrier's ETA with the operations team."), true, null);
			return new MaritimeAiClientResult("cdg262-context-derived-stub", "offline-evaluation", "cdg262.context-evaluation.v1",
				interpretation, null);
		}

		List<MaritimeAiEvidencePackage> inputs() { return List.copyOf(inputs); }
		void clear() { inputs.clear(); }

		private static MaritimeAiClaim.RiskReview reviewFor(MaritimeAiConnectionRiskContext risk) {
			MaritimeAiClaim.RiskReview.SelectedWindowState state = switch (risk.plannedWindow().state()) {
				case AVAILABLE -> MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE;
				case INVALID -> MaritimeAiClaim.RiskReview.SelectedWindowState.INVALID;
				case INCOMPLETE -> MaritimeAiClaim.RiskReview.SelectedWindowState.INCOMPLETE;
			};
			return new MaritimeAiClaim.RiskReview(risk.riskLevel(), risk.riskBasis(), state, risk.arrivalEstimate().state());
		}
	}
}
