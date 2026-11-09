package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.shipment.SyntheticPortCatalogTestConfiguration;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL-backed regression for importer reassignment and whole-assessment history visibility. */
@SpringBootTest(properties = { "app.maritime-ai.enabled=true", "app.maritime-context.demo-enabled=false",
		"app.ais.enabled=false", "app.maritime-ai.controlled-replay-stub-enabled=false" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import({
		MaritimeAiCrossShipmentEvaluationIntegrationTests.CrossShipmentConfiguration.class,
		SyntheticPortCatalogTestConfiguration.class })
@Transactional
class MaritimeAiAssessmentHistoryAuthorizationIntegrationTests {
	private static final String PORT = "Synthetic Multi-source Port";
	private static final String PRIVATE_DEADLINE = "2026-10-02T10:00:00Z";
	private static final String PRIVATE_FAILURE_MARKER = "PRIVATE_FAILURE_MARKER";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;
	@Autowired ObjectMapper json;
	@Autowired MaritimeAiCrossShipmentEvaluationIntegrationTests.ContextDerivedClient client;

	private Long managingCompanyId;
	private Long managingUserId;
	private Long originalImporterCompanyId;
	private Long successorImporterCompanyId;
	private Long successorUserId;
	private String managingToken;
	private String originalImporterToken;
	private String successorImporterToken;
	private String unrelatedToken;
	private Long shipmentId;

	@BeforeEach
	void setUp() throws Exception {
		client.clear();
		managingCompanyId = companyId("HARBOURLINE_DEMO");
		originalImporterCompanyId = companyId("STRAITS_FRESH_DEMO");
		successorImporterCompanyId = createCompany("CDG289_SUCCESSOR_" + randomSuffix());
		Long unrelatedCompanyId = createCompany("CDG289_UNRELATED_" + randomSuffix());

		String managingEmail = createAccount(managingCompanyId, "FREIGHT_FORWARDER", "manager");
		managingUserId = userId(managingEmail);
		managingToken = login(managingEmail);
		originalImporterToken = login(createAccount(originalImporterCompanyId, "IMPORTER", "original-importer"));
		String successorEmail = createAccount(successorImporterCompanyId, "IMPORTER", "successor-importer");
		successorUserId = userId(successorEmail);
		successorImporterToken = login(successorEmail);
		unrelatedToken = login(createAccount(unrelatedCompanyId, "IMPORTER", "unrelated-importer"));

		shipmentId = createShipment();
		createRelationship(originalImporterCompanyId);
		linkImporter(originalImporterCompanyId);
		saveDeadline();
	}

	@Test
	void legacyOrdinaryHistoryRemainsVisibleWhenScenarioContextIsAbsentOrNull() throws Exception {
		MvcResult generated = generate(originalImporterToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("GENERATED")).andReturn();
		String assessmentId = JsonPath.read(generated.getResponse().getContentAsString(), "$.assessmentId");
		assertThat(jdbc.queryForObject("SELECT record_type FROM maritime_ai_assessments WHERE assessment_id = ?", String.class,
				assessmentId)).isEqualTo(MaritimeAiStoredAssessmentContent.SHIPMENT_ASSESSMENT);

		jdbc.update("UPDATE maritime_ai_assessments SET output_json = (output_json::jsonb - 'recordType' - 'scenarioContext')::text WHERE assessment_id = ?",
				assessmentId);
		MvcResult absentContext = history(originalImporterToken).andExpect(status().isOk()).andReturn();
		assertThat(json.readTree(absentContext.getResponse().getContentAsString()).size()).isEqualTo(1);
		assertThat(absentContext.getResponse().getContentAsString()).contains(assessmentId);

		jdbc.update("UPDATE maritime_ai_assessments SET output_json = jsonb_set(output_json::jsonb, '{scenarioContext}', 'null'::jsonb, TRUE)::text WHERE assessment_id = ?",
				assessmentId);
		MvcResult nullContext = history(originalImporterToken).andExpect(status().isOk()).andReturn();
		assertThat(json.readTree(nullContext.getResponse().getContentAsString()).size()).isEqualTo(1);
		assertThat(nullContext.getResponse().getContentAsString()).contains(assessmentId);
	}

	@Test
	void authorizedHistoryIsScopedToTheCapturedImporterAndTheManagingCompany() throws Exception {
		MvcResult success = generate(originalImporterToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("GENERATED")).andReturn();
		String successfulAssessmentId = JsonPath.read(success.getResponse().getContentAsString(), "$.assessmentId");

		client.failNext(PRIVATE_FAILURE_MARKER);
		MvcResult failed = generate(originalImporterToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FAILED"))
				.andExpect(jsonPath("$.limitation").value(PRIVATE_FAILURE_MARKER)).andReturn();
		String failedAssessmentId = JsonPath.read(failed.getResponse().getContentAsString(), "$.assessmentId");

		// Model an older envelope written before importer provenance existed.
		String legacyAssessmentId = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO maritime_ai_assessments
					(shipment_id, assessment_id, owning_company_id, bundle_version, bundle_hash, assessment_cutoff_utc,
					 provider, model, prompt_version, generation_state, validation_status, output_json, failure_metadata, created_at)
				SELECT shipment_id, ?, owning_company_id, bundle_version, bundle_hash, assessment_cutoff_utc,
					provider, model, prompt_version, generation_state, validation_status,
					(output_json::jsonb - 'historyScope')::text, failure_metadata, created_at
				FROM maritime_ai_assessments WHERE assessment_id = ?
				""", legacyAssessmentId, successfulAssessmentId);
		entityManager.clear();
		// The original list path mapped this unfiltered shipment-scoped row set directly to responses.
		String unfilteredHistoryContent = jdbc.queryForList(
				"SELECT COALESCE(output_json, '') || COALESCE(failure_metadata, '') FROM maritime_ai_assessments WHERE shipment_id = ? ORDER BY created_at DESC",
				String.class, shipmentId).toString();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE shipment_id = ?", Long.class, shipmentId))
				.isEqualTo(3L);
		assertThat(unfilteredHistoryContent).contains(PRIVATE_DEADLINE, PRIVATE_FAILURE_MARKER);

		MvcResult originalHistory = history(originalImporterToken).andExpect(status().isOk()).andReturn();
		JsonNode originalRows = json.readTree(originalHistory.getResponse().getContentAsString());
		assertThat(originalRows.size()).isEqualTo(2);
		assertThat(originalHistory.getResponse().getContentAsString())
				.contains(PRIVATE_DEADLINE, PRIVATE_FAILURE_MARKER)
				.contains("\"state\":\"GENERATED\"", "\"state\":\"FAILED\"")
				.doesNotContain("historyScope", "importerCompanyId", "owningCompanyId", legacyAssessmentId);
		assertThat(originalHistory.getResponse().getContentAsString()).contains(successfulAssessmentId, failedAssessmentId);

		MvcResult managerHistory = history(managingToken).andExpect(status().isOk()).andReturn();
		assertThat(json.readTree(managerHistory.getResponse().getContentAsString()).size()).isEqualTo(3);
		assertThat(managerHistory.getResponse().getContentAsString())
				.contains(PRIVATE_DEADLINE, PRIVATE_FAILURE_MARKER, legacyAssessmentId);

		MvcResult unrelatedDenied = history(unrelatedToken).andExpect(status().isForbidden()).andReturn();
		assertDeniedBodyHasNoAssessmentContent(unrelatedDenied, successfulAssessmentId, PRIVATE_DEADLINE, PRIVATE_FAILURE_MARKER);

		createRelationship(successorImporterCompanyId);
		linkImporter(successorImporterCompanyId);
		MvcResult successorHistory = history(successorImporterToken).andExpect(status().isOk()).andReturn();
		assertThat(successorHistory.getResponse().getContentAsString()).isEqualTo("[]");
		assertDeniedBodyHasNoAssessmentContent(successorHistory, successfulAssessmentId, PRIVATE_DEADLINE, PRIVATE_FAILURE_MARKER);

		// The shipment-scoped collection is the only current read route. If a detail route is added,
		// an authorized successor must still fail the assessment-level ownership check.
		MvcResult directDetail = mvc.perform(get("/api/maritime-ai-assessments/" + successfulAssessmentId)
				.header("Authorization", "Bearer " + successorImporterToken)).andExpect(status().is4xxClientError()).andReturn();
		assertDeniedBodyHasNoAssessmentContent(directDetail, successfulAssessmentId, PRIVATE_DEADLINE, PRIVATE_FAILURE_MARKER);

		MvcResult formerImporterDenied = history(originalImporterToken).andExpect(status().isForbidden()).andReturn();
		assertDeniedBodyHasNoAssessmentContent(formerImporterDenied, successfulAssessmentId, PRIVATE_DEADLINE, PRIVATE_FAILURE_MARKER);

		MvcResult managerAfterReassignment = history(managingToken).andExpect(status().isOk()).andReturn();
		assertThat(json.readTree(managerAfterReassignment.getResponse().getContentAsString()).size()).isEqualTo(3);
		assertThat(managerAfterReassignment.getResponse().getContentAsString()).contains(PRIVATE_DEADLINE, PRIVATE_FAILURE_MARKER);

		jdbc.update("UPDATE users SET status = 'REMOVED', session_generation = session_generation + 1 WHERE id = ?", successorUserId);
		entityManager.clear();
		MvcResult revokedUserDenied = history(successorImporterToken).andExpect(status().isUnauthorized()).andReturn();
		assertDeniedBodyHasNoAssessmentContent(revokedUserDenied, successfulAssessmentId, PRIVATE_DEADLINE, PRIVATE_FAILURE_MARKER);
	}

	@Test
	void importerHistoryFailsClosedForMissingMalformedUnsupportedAndFractionalProvenance() throws Exception {
		MvcResult generated = generate(originalImporterToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("GENERATED")).andReturn();
		String validAssessmentId = JsonPath.read(generated.getResponse().getContentAsString(), "$.assessmentId");
		List<String> deniedRows = new java.util.ArrayList<>();
		int row = 0;
		deniedRows.add(copyAssessmentWithoutHistoryScope(validAssessmentId, row++));
		List<String> malformedScopes = List.of(
				"null",
				"{}",
				"{\"version\":\"one\",\"importerCompanyId\":" + originalImporterCompanyId + "}",
				"{\"version\":2,\"importerCompanyId\":" + originalImporterCompanyId + "}",
				"{\"version\":1,\"importerCompanyId\":null}",
				"{\"version\":1,\"importerCompanyId\":0}",
				"{\"version\":1,\"importerCompanyId\":" + successorImporterCompanyId + "}",
				"{\"version\":1.5,\"importerCompanyId\":" + originalImporterCompanyId + "}",
				"{\"version\":1,\"importerCompanyId\":" + originalImporterCompanyId + ".75}");
		for (String scope : malformedScopes) {
			deniedRows.add(copyAssessmentWithHistoryScope(validAssessmentId, scope, row++));
		}

		MvcResult importerHistory = history(originalImporterToken).andExpect(status().isOk()).andReturn();
		String body = importerHistory.getResponse().getContentAsString();
		assertThat(json.readTree(body).size()).isEqualTo(1);
		assertThat(body).contains(validAssessmentId);
		for (int index = 0; index < deniedRows.size(); index++) {
			assertThat(body).doesNotContain(deniedRows.get(index), privateMarker(index));
		}
	}

	@Test
	void managingCompanyCannotReadAssessmentWhosePersistedOwnerDoesNotMatchShipmentOwner() throws Exception {
		MvcResult generated = generate(originalImporterToken)
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("GENERATED")).andReturn();
		String validAssessmentId = JsonPath.read(generated.getResponse().getContentAsString(), "$.assessmentId");
		String mismatchedOwnerAssessmentId = copyAssessmentWithDifferentOwner(validAssessmentId);

		MvcResult managerHistory = history(managingToken).andExpect(status().isOk()).andReturn();
		assertThat(json.readTree(managerHistory.getResponse().getContentAsString()).size()).isEqualTo(1);
		assertThat(managerHistory.getResponse().getContentAsString())
				.contains(validAssessmentId)
				.doesNotContain(mismatchedOwnerAssessmentId, "OWNER_MISMATCH_PRIVATE_MARKER");

		MvcResult importerHistory = history(originalImporterToken).andExpect(status().isOk()).andReturn();
		assertThat(json.readTree(importerHistory.getResponse().getContentAsString()).size()).isEqualTo(1);
		assertThat(importerHistory.getResponse().getContentAsString())
				.contains(validAssessmentId)
				.doesNotContain(mismatchedOwnerAssessmentId, "OWNER_MISMATCH_PRIVATE_MARKER");
	}

	private String copyAssessmentWithoutHistoryScope(String sourceAssessmentId, int markerIndex) {
		String assessmentId = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO maritime_ai_assessments
					(shipment_id, assessment_id, owning_company_id, bundle_version, bundle_hash, assessment_cutoff_utc,
					 provider, model, prompt_version, generation_state, validation_status, output_json, failure_metadata, created_at)
				SELECT shipment_id, ?, owning_company_id, bundle_version, bundle_hash, assessment_cutoff_utc,
					provider, model, prompt_version, generation_state, validation_status,
					(output_json::jsonb - 'historyScope')::text, ?, created_at
				FROM maritime_ai_assessments WHERE assessment_id = ?
				""", assessmentId, privateMarker(markerIndex), sourceAssessmentId);
		return assessmentId;
	}

	private String copyAssessmentWithHistoryScope(String sourceAssessmentId, String scopeJson, int markerIndex) {
		String assessmentId = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO maritime_ai_assessments
					(shipment_id, assessment_id, owning_company_id, bundle_version, bundle_hash, assessment_cutoff_utc,
					 provider, model, prompt_version, generation_state, validation_status, output_json, failure_metadata, created_at)
				SELECT shipment_id, ?, owning_company_id, bundle_version, bundle_hash, assessment_cutoff_utc,
					provider, model, prompt_version, generation_state, validation_status,
					jsonb_set(output_json::jsonb, '{historyScope}', ?::jsonb, TRUE)::text, ?, created_at
				FROM maritime_ai_assessments WHERE assessment_id = ?
				""", assessmentId, scopeJson, privateMarker(markerIndex), sourceAssessmentId);
		return assessmentId;
	}

	private String copyAssessmentWithDifferentOwner(String sourceAssessmentId) {
		String assessmentId = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO maritime_ai_assessments
					(shipment_id, assessment_id, owning_company_id, bundle_version, bundle_hash, assessment_cutoff_utc,
					 provider, model, prompt_version, generation_state, validation_status, output_json, failure_metadata, created_at)
				SELECT shipment_id, ?, ?, bundle_version, bundle_hash, assessment_cutoff_utc,
					provider, model, prompt_version, generation_state, validation_status, output_json, ?, created_at
				FROM maritime_ai_assessments WHERE assessment_id = ?
				""", assessmentId, successorImporterCompanyId, "OWNER_MISMATCH_PRIVATE_MARKER", sourceAssessmentId);
		return assessmentId;
	}

	private static String privateMarker(int index) {
		return "MALFORMED_SCOPE_PRIVATE_MARKER_" + index;
	}

	private void assertDeniedBodyHasNoAssessmentContent(MvcResult result, String assessmentId, String... privateValues) throws Exception {
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain(assessmentId, "GENERATED", "FAILED", "ABSTAINED", "bundleHash", "provider", "model");
		assertThat(body).doesNotContain(privateValues);
	}

	private org.springframework.test.web.servlet.ResultActions generate(String token) throws Exception {
		return mvc.perform(post("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
				.header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions history(String token) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
				.header("Authorization", "Bearer " + token));
	}

	private Long createShipment() throws Exception {
		String body = """
				{"shipmentReference":"CDG289-PRIVACY-%s","origin":"Synthetic origin","destination":"Synthetic destination",
				"transshipmentPort":"%s","motherVessel":"Synthetic Multi-source Vessel","motherVesselImo":"9000002",
				"plannedMotherArrivalAt":"2026-10-01T05:00:00+08:00","feederVessel":"Synthetic Multi-source Feeder",
				"plannedFeederDepartureAt":"2026-10-01T14:00:00+08:00"}
				""".formatted(UUID.randomUUID(), PORT);
		MvcResult response = mvc.perform(post("/api/shipments").header("Authorization", "Bearer " + managingToken)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
		return ((Number) JsonPath.read(response.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private void createRelationship(Long importerCompanyId) {
		jdbc.update("""
				INSERT INTO forwarder_importer_relationships
					(forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
				VALUES (?, ?, TRUE, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
				""", managingCompanyId, importerCompanyId, managingUserId, managingUserId);
	}

	private void linkImporter(Long importerCompanyId) throws Exception {
		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + managingToken).contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":%d}".formatted(importerCompanyId)))
				.andExpect(status().isOk());
		entityManager.clear();
	}

	private void saveDeadline() throws Exception {
		mvc.perform(patch("/api/shipments/" + shipmentId + "/transshipment-arrival-deadline")
				.header("Authorization", "Bearer " + originalImporterToken).contentType(MediaType.APPLICATION_JSON)
				.content("{\"transshipmentArrivalDeadline\":\"" + PRIVATE_DEADLINE
						+ "\",\"expectedTransshipmentPort\":\"" + PORT + "\"}"))
				.andExpect(status().isOk());
		entityManager.clear();
	}

	private String createAccount(Long companyId, String role, String tag) {
		String email = "cdg289-" + tag + "-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ? FROM roles WHERE code = ?
				""", "CDG-289 " + tag, email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private String login(String email) throws Exception {
		MvcResult response = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Example123\"}"))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(response.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany(String code) {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-289 " + code);
		return companyId(code);
	}

	private Long userId(String email) {
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	private static String randomSuffix() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}
}
