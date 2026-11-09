package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.shipment.PortCatalog.Coordinates;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CDG-47. The likely impact on the linked importer's deadline selects the recommendation on the detail, and the list
 * carries the same comparison so impacted cases rank first within a risk level and can be kept on their own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(ShipmentRecommendationClock.class)
class ShipmentImpactIntegrationTests {

	private static final Instant NOW = ShipmentRecommendationClock.NOW;
	private static final BigDecimal SPEED = new BigDecimal("12");

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired ObjectMapper json;
	@Autowired EntityManager entityManager;
	@Autowired LatestAisPositions latestPositions;
	@Autowired ShipmentService shipments;

	private final Coordinates singapore = PortCatalog.load().locate("Singapore").orElseThrow();
	private final String prefix = "HBL-47-" + UUID.randomUUID().toString().substring(0, 8) + "-";
	private String forwarderEmail;
	private String forwarderToken;
	private String importerToken;
	private String outsiderToken;
	private Long importerCompanyId;
	private Long otherImporterCompanyId;

	@BeforeEach
	void accounts() throws Exception {
		latestPositions.clear();
		Long forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		otherImporterCompanyId = createCompany();
		forwarderEmail = account("FREIGHT_FORWARDER", forwarderCompanyId);
		forwarderToken = login(forwarderEmail);
		importerToken = login(account("IMPORTER", importerCompanyId));
		outsiderToken = login(account("FREIGHT_FORWARDER", createCompany()));
	}

	@AfterEach
	void clearPositions() {
		latestPositions.clear();
	}

	@Test
	void aPredictedArrivalAfterTheImportersDeadlineSelectsTheInterventionForEveryAuthorisedViewer() throws Exception {
		long late = linked("LATE", Duration.ofHours(6), importerCompanyId, NOW.minus(Duration.ofHours(1)));
		long onTime = linked("ONTIME", Duration.ofHours(6), importerCompanyId, NOW.plus(Duration.ofHours(1)));

		JsonNode lateDetail = read(forwarderToken, late);
		assertThat(lateDetail.get("risk").get("level").asText()).isEqualTo("HIGH");
		assertThat(lateDetail.get("delayComparison").get("outcome").asText()).isEqualTo("EXCEEDS_DEADLINE");
		JsonNode recommendation = lateDetail.get("recommendation");
		assertThat(recommendation.get("available").asBoolean()).isTrue();
		assertThat(recommendation.get("unavailable").isNull()).isTrue();
		assertThat(recommendation.get("response").get("code").asText()).isEqualTo("PREPARE_INTERVENTION");
		assertThat(recommendation.get("impact").get("outcome").asText()).isEqualTo("AFFECTS_CONSTRAINTS");
		assertThat(recommendation.get("impact").get("detail").isNull()).isTrue();

		JsonNode onTimeDetail = read(forwarderToken, onTime);
		assertThat(onTimeDetail.get("delayComparison").get("outcome").asText()).isEqualTo("NO_PREDICTED_IMPACT");
		assertThat(onTimeDetail.get("recommendation").get("response").get("code").asText()).isEqualTo("WATCH_CONNECTION");
		assertThat(onTimeDetail.get("recommendation").get("impact").get("outcome").asText())
				.isEqualTo("NO_PREDICTED_IMPACT");

		JsonNode importerDetail = read(importerToken, late);
		assertThat(importerDetail.get("recommendation")).isEqualTo(recommendation);
		assertThat(importerDetail.get("delayComparison")).isEqualTo(lateDetail.get("delayComparison"));

		mvc.perform(get("/api/shipments/" + late).header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.recommendation").doesNotExist())
				.andExpect(jsonPath("$.delayComparison").doesNotExist());
	}

	@Test
	void anotherImportersDeadlineDoesNotSelectAResponse() throws Exception {
		long id = linked("OTHER", Duration.ofHours(6), importerCompanyId, null);
		recordDeadline(id, otherImporterCompanyId, NOW.minus(Duration.ofHours(1)));
		entityManager.clear();

		JsonNode detail = read(forwarderToken, id);
		assertThat(detail.get("delayComparison").get("reason").asText()).isEqualTo("MISSING_CONSTRAINT");
		assertThat(detail.get("recommendation").get("available").asBoolean()).isFalse();
		assertThat(detail.get("recommendation").get("unavailable").asText())
				.isEqualTo(ShipmentRecommendation.IMPACT_UNAVAILABLE + DelayComparison.MISSING_CONSTRAINT_MESSAGE);
		assertThat(listed(forwarderToken).get(0).get("delayComparison")).isEqualTo(detail.get("delayComparison"));
	}

	@Test
	void theListRanksImpactedCasesFirstWithinTheirRiskAndCanKeepOnlyThem() throws Exception {
		long onTime = linked("A", Duration.ofHours(6), importerCompanyId, NOW.plus(Duration.ofHours(1)));
		long late = linked("B", Duration.ofHours(7), importerCompanyId, NOW.minus(Duration.ofHours(1)));
		long noDeadline = linked("C", Duration.ofHours(6).plusMinutes(30), importerCompanyId, null);

		List<JsonNode> ranked = listed(forwarderToken, "sort", "risk");
		assertThat(ranked).extracting(row -> row.get("id").asLong()).containsExactly(late, onTime, noDeadline);
		assertThat(ranked).extracting(row -> row.get("risk").get("level").asText()).containsOnly("HIGH");
		assertThat(ranked.get(0).get("delayComparison")).isEqualTo(read(forwarderToken, late).get("delayComparison"));
		assertThat(ranked.get(0).has("recommendation")).isFalse();
		assertThat(ranked.get(2).get("delayComparison").get("reason").asText()).isEqualTo("MISSING_CONSTRAINT");

		assertThat(listed(forwarderToken, "exceedsDeadline", "true"))
				.extracting(row -> row.get("id").asLong()).containsExactly(late);
		assertThat(listed(importerToken, "sort", "risk", "exceedsDeadline", "true"))
				.extracting(row -> row.get("id").asLong()).containsExactly(late);
		assertThat(listed(outsiderToken, "exceedsDeadline", "true")).isEmpty();

		mvc.perform(get("/api/shipments").param("exceedsDeadline", "maybe")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.exceedsDeadline").value("exceedsDeadline must be true or false"));
	}

	@Test
	void theExceedsDeadlineFilterIsAppliedBeforeThePageIsCut() throws Exception {
		List<Long> late = new ArrayList<>();
		for (int i = 0; i < ShipmentService.PAGE_SIZE + 1; i++) {
			late.add(linked("LATE-" + String.format("%02d", i), Duration.ofHours(6), importerCompanyId,
					NOW.minus(Duration.ofHours(1))));
		}
		long onTime = linked("ONTIME", Duration.ofHours(6), importerCompanyId, NOW.plus(Duration.ofHours(1)));
		long noDeadline = linked("NODEADLINE", Duration.ofHours(6), importerCompanyId, null);
		Long forwarderId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, forwarderEmail);
		AuthenticatedUser forwarder = new AuthenticatedUser(forwarderId, forwarderEmail, Role.FREIGHT_FORWARDER,
				Instant.parse("2030-01-01T00:00:00Z"));
		ShipmentFilterCriteria exceeding = ShipmentFilterCriteria.of(prefix, null, null, null, null, null, true);

		for (ShipmentSort sort : List.of(ShipmentSort.CREATED_DATE_DESCENDING, ShipmentSort.RISK_FIRST)) {
			ShipmentPage first = shipments.listPage(forwarder, exceeding, 1, sort);
			ShipmentPage second = shipments.listPage(forwarder, exceeding, 2, sort);
			assertThat(first.total()).isEqualTo(late.size());
			assertThat(second.total()).isEqualTo(late.size());
			assertThat(first.items()).hasSize(ShipmentService.PAGE_SIZE);
			assertThat(second.items()).hasSize(1);
			List<Long> paged = new ArrayList<>(first.items().stream().map(ShipmentResponse::id).toList());
			paged.addAll(second.items().stream().map(ShipmentResponse::id).toList());
			assertThat(paged).containsExactlyInAnyOrderElementsOf(late).doesNotContain(onTime, noDeadline);
			assertThat(paged).doesNotHaveDuplicates();
		}
		assertThat(shipments.listPage(forwarder, exceeding, 3, ShipmentSort.CREATED_DATE_DESCENDING).items()).isEmpty();
	}

	/** A shipment linked to the importer whose mother vessel is at the port now, so its estimated arrival is NOW. */
	private long linked(String label, Duration remaining, Long importer, Instant deadline) throws Exception {
		String vessel = "CDG47" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		latestPositions.record(new AisPosition(mmsi(), vessel, BigDecimal.valueOf(singapore.latitude()),
				BigDecimal.valueOf(singapore.longitude()), SPEED, null, null, 0, true, null, NOW,
				AisPositionSource.SEEDED));
		long id = create(prefix + label, "MV " + vessel, NOW, NOW.plus(remaining));
		jdbc.update("UPDATE shipments SET importer_company_id = ? WHERE id = ?", importer, id);
		if (deadline != null) {
			recordDeadline(id, importer, deadline);
		}
		entityManager.clear();
		return id;
	}

	private void recordDeadline(long shipmentId, Long importer, Instant deadline) {
		jdbc.update("""
				INSERT INTO shipment_importer_constraints
					(shipment_id, importer_company_id, transshipment_port, transshipment_arrival_deadline)
				VALUES (?, ?, 'Singapore', ?)
				""", shipmentId, importer, Timestamp.from(deadline));
	}

	private long create(String reference, String motherVessel, Instant arrival, Instant departure) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + forwarderToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Jakarta",
						  "destination": "Rotterdam",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "%s",
						  "plannedMotherArrivalAt": "%s",
						  "feederVessel": "MV Feeder",
						  "plannedFeederDepartureAt": "%s"
						}
						""".formatted(reference, motherVessel, arrival.atOffset(ZoneOffset.UTC),
						departure.atOffset(ZoneOffset.UTC))))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private JsonNode read(String token, long id) throws Exception {
		MvcResult result = mvc.perform(get("/api/shipments/" + id).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		return json.readTree(result.getResponse().getContentAsString());
	}

	private List<JsonNode> listed(String token, String... params) throws Exception {
		var request = get("/api/shipments").param("reference", prefix).header("Authorization", "Bearer " + token);
		for (int i = 0; i < params.length; i += 2) {
			request.param(params[i], params[i + 1]);
		}
		JsonNode body = json.readTree(mvc.perform(request).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		List<JsonNode> rows = new ArrayList<>();
		body.forEach(rows::add);
		return rows;
	}

	private String account(String role, Long companyId) {
		String email = "cdg47-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 47 User', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private String login(String email) throws Exception {
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createCompany() {
		String code = "CDG47" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-47 Other Company");
		return companyId(code);
	}

	private static String mmsi() {
		return "5" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
	}
}
