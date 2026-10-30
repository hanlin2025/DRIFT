package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.shipment.exception.RecommendationDecisionRejectedException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(RecommendationDecisionApiIntegrationTests.ImpactConfiguration.class)
class RecommendationDecisionApiIntegrationTests {

	private static final String NO_ACTION = ShipmentRecommendedResponse.Code.NO_ACTION.wording();
	private static final String MONITOR = ShipmentRecommendedResponse.Code.MONITOR_CONSTRAINTS.wording();

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired CompanyRepository companies;
	@Autowired AccountRoleRepository roles;
	@Autowired UserAccountRepository users;
	@Autowired ShipmentRepository shipments;
	@Autowired ChosenImpact impacts;

	private String token;
	private long userId;

	@BeforeEach
	void account() throws Exception {
		impacts.use(ShipmentRecommendationImpact.notReturned());
		Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = roles.findByCode(Role.FREIGHT_FORWARDER).orElseThrow();
		UserAccount user = users.saveAndFlush(new UserAccount("Decision User", "decision-api@example.test",
				passwords.encode("Example123"), role, company));
		userId = user.getId();
		token = tokenFor("decision-api@example.test");
	}

	@Test
	void anUnknownShipmentIsNotFoundAndStoresNothing() throws Exception {
		int before = count("recommendation_decisions");

		decide(9_999_999_999L, "{\"outcome\":\"APPROVED\"}")
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));

		assertThat(count("recommendation_decisions")).isEqualTo(before);
	}

	@Test
	void aMissingTokenIsRejected() throws Exception {
		mvc.perform(patch("/api/shipments/1/recommendation-decision")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"outcome\":\"APPROVED\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	@Test
	void anUnavailableRecommendationIsRejectedAndStoresNothing() throws Exception {
		long shipmentId = createShipment();
		Map<String, Object> before = shipmentRow(shipmentId);
		int history = historyCount(shipmentId);
		int risks = riskCount(shipmentId);
		int mail = mailCount(shipmentId);

		detail(shipmentId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationDecision").value(nullValue()));

		for (String body : new String[] {
				"{\"outcome\":\"APPROVED\"}",
				"{\"outcome\":\"MODIFIED\",\"selectedResponse\":\"MONITOR_CONSTRAINTS\"}",
				"{\"outcome\":\"DISMISSED\"}" }) {
			decide(shipmentId, body)
					.andExpect(status().isConflict())
					.andExpect(header().string("Cache-Control", "no-store"))
					.andExpect(jsonPath("$.message").value(RecommendationDecisionRejectedException.UNAVAILABLE));
		}

		detail(shipmentId).andExpect(jsonPath("$.recommendationDecision").value(nullValue()));
		assertThat(decisionCount(shipmentId)).isZero();
		assertThat(shipmentRow(shipmentId)).isEqualTo(before);
		assertThat(historyCount(shipmentId)).isEqualTo(history);
		assertThat(riskCount(shipmentId)).isEqualTo(risks);
		assertThat(mailCount(shipmentId)).isEqualTo(mail);
	}

	@Test
	void aMalformedDecisionIsRejectedAndStoresNothing() throws Exception {
		long shipmentId = createShipment();

		decide(shipmentId, "{\"outcome\":\"HOLD\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"));
		decide(shipmentId, "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.outcome").exists());
		decide(shipmentId, "{\"outcome\":\"MODIFIED\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.selectedResponse").value("A modification needs the selected response"));
		decide(shipmentId, "{\"outcome\":\"APPROVED\",\"selectedResponse\":\"MONITOR_CONSTRAINTS\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.selectedResponse")
						.value("A selected response is stored only for a modification"));
		decide(shipmentId, "{\"outcome\":\"DISMISSED\",\"selectedResponse\":\"MONITOR_CONSTRAINTS\"}")
				.andExpect(status().isBadRequest());
		decide(shipmentId, "{\"outcome\":\"MODIFIED\",\"selectedResponse\":null}")
				.andExpect(status().isBadRequest());
		decide(shipmentId, "{\"outcome\":\"MODIFIED\",\"selectedResponse\":\"Call the terminal\"}")
				.andExpect(status().isBadRequest());
		decide(shipmentId, "{\"outcome\":\"approved\"}")
				.andExpect(status().isBadRequest());
		decide(shipmentId, "")
				.andExpect(status().isBadRequest());

		mvc.perform(patch("/api/shipments/not-a-number/recommendation-decision")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"outcome\":\"APPROVED\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));

		assertThat(decisionCount(shipmentId)).isZero();
	}

	@Test
	void approveModifyAndDismissReplaceTheCurrentDecisionAndTheDetailReturnsIt() throws Exception {
		impacts.use(ShipmentRecommendationImpact.noPredictedImpact());
		long shipmentId = createShipment();
		Map<String, Object> before = shipmentRow(shipmentId);
		int history = historyCount(shipmentId);
		int risks = riskCount(shipmentId);
		int mail = mailCount(shipmentId);

		decide(shipmentId, "{\"outcome\":\"MODIFIED\",\"selectedResponse\":\"NO_ACTION\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.selectedResponse").value("A modification selects a different response"));
		assertThat(decisionCount(shipmentId)).isZero();

		decide(shipmentId, "{\"outcome\":\"APPROVED\"}")
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationWording").value(NO_ACTION))
				.andExpect(jsonPath("$.selectedResponseName").value(nullValue()))
				.andExpect(jsonPath("$.selectedResponseWording").value(nullValue()))
				.andExpect(jsonPath("$.decidedByUserId").value(userId))
				.andExpect(jsonPath("$.decidedByName").value("Decision User"))
				.andExpect(jsonPath("$.decidedAt").exists());

		detail(shipmentId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationWording").value(NO_ACTION))
				.andExpect(jsonPath("$.recommendationDecision.decidedByUserId").value(userId))
				.andExpect(jsonPath("$.recommendationDecision.decidedByName").value("Decision User"))
				.andExpect(jsonPath("$.recommendationDecision.decidedAt").exists())
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		decide(shipmentId, "{\"outcome\":\"MODIFIED\",\"selectedResponse\":\"MONITOR_CONSTRAINTS\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("MODIFIED"))
				.andExpect(jsonPath("$.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationWording").value(NO_ACTION))
				.andExpect(jsonPath("$.selectedResponseName").value("Monitor"))
				.andExpect(jsonPath("$.selectedResponseWording").value(MONITOR));

		detail(shipmentId)
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("MODIFIED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationDecision.selectedResponseName").value("Monitor"))
				.andExpect(jsonPath("$.recommendationDecision.selectedResponseWording").value(MONITOR));

		decide(shipmentId, "{\"outcome\":\"DISMISSED\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("DISMISSED"))
				.andExpect(jsonPath("$.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationWording").value(NO_ACTION))
				.andExpect(jsonPath("$.selectedResponseName").value(nullValue()))
				.andExpect(jsonPath("$.selectedResponseWording").value(nullValue()));

		detail(shipmentId)
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("DISMISSED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationDecision.selectedResponseName").value(nullValue()))
				.andExpect(jsonPath("$.recommendationDecision.decidedByName").value("Decision User"));

		assertThat(decisionCount(shipmentId)).isEqualTo(1);
		assertThat(shipmentRow(shipmentId)).isEqualTo(before);
		assertThat(historyCount(shipmentId)).isEqualTo(history);
		assertThat(riskCount(shipmentId)).isEqualTo(risks);
		assertThat(mailCount(shipmentId)).isEqualTo(mail);
		assertThat(NO_ACTION).doesNotContain("planned connection window");
	}

	@Test
	void anArchivedShipmentRejectsANewDecisionAndKeepsTheStoredOne() throws Exception {
		impacts.use(ShipmentRecommendationImpact.noPredictedImpact());
		long shipmentId = createShipment();
		decide(shipmentId, "{\"outcome\":\"APPROVED\"}").andExpect(status().isOk());

		Shipment shipment = shipments.findById(shipmentId).orElseThrow();
		shipment.archive();
		shipments.saveAndFlush(shipment);
		Map<String, Object> before = shipmentRow(shipmentId);

		decide(shipmentId, "{\"outcome\":\"DISMISSED\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(RecommendationDecisionRejectedException.ARCHIVED));

		detail(shipmentId)
				.andExpect(jsonPath("$.status").value("ARCHIVED"))
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"));
		assertThat(decisionCount(shipmentId)).isEqualTo(1);
		assertThat(shipmentRow(shipmentId)).isEqualTo(before);
		assertThat(mailCount(shipmentId)).isZero();
	}

	@Test
	void onlyACompanyThatCanViewTheShipmentCanDecide() throws Exception {
		impacts.use(ShipmentRecommendationImpact.noPredictedImpact());
		long shipmentId = createShipment();
		decide(shipmentId, "{\"outcome\":\"APPROVED\"}").andExpect(status().isOk());

		String otherCode = "CDG229-" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, true)", otherCode, "Other Logistics");
		Company other = companies.findByCode(otherCode).orElseThrow();
		AccountRole forwarder = roles.findByCode(Role.FREIGHT_FORWARDER).orElseThrow();
		users.saveAndFlush(new UserAccount("Other Company", "decision-other-company@example.test",
				passwords.encode("Example123"), forwarder, other));
		String otherToken = tokenFor("decision-other-company@example.test");

		decide(otherToken, shipmentId, "{\"outcome\":\"DISMISSED\"}")
				.andExpect(status().isForbidden())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE));
		detail(otherToken, shipmentId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE));
		decide(otherToken, 9_999_999_999L, "{\"outcome\":\"APPROVED\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));

		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id)
				SELECT 'Unassigned User', ?, ?, id FROM roles WHERE code = ?
				""", "decision-unassigned@example.test", passwords.encode("Example123"), "FREIGHT_FORWARDER");
		String unassigned = tokenFor("decision-unassigned@example.test");
		decide(unassigned, shipmentId, "{\"outcome\":\"DISMISSED\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentAccessForbiddenException.MESSAGE));

		detail(shipmentId)
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationDecision.decidedByName").value("Decision User"));
		assertThat(decisionCount(shipmentId)).isEqualTo(1);

		Long importerId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, "STRAITS_FRESH_DEMO");
		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationDecision.decidedByName").value("Decision User"));
		assertThat(decisionCount(shipmentId)).isEqualTo(1);
		Company importer = companies.findByCode("STRAITS_FRESH_DEMO").orElseThrow();
		AccountRole importerRole = roles.findByCode(Role.IMPORTER).orElseThrow();
		users.saveAndFlush(new UserAccount("Linked Importer", "decision-linked-importer@example.test",
				passwords.encode("Example123"), importerRole, importer));
		String importerToken = tokenFor("decision-linked-importer@example.test");

		decide(importerToken, shipmentId, "{\"outcome\":\"DISMISSED\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("DISMISSED"))
				.andExpect(jsonPath("$.recommendationName").value("No action"))
				.andExpect(jsonPath("$.decidedByName").value("Linked Importer"))
				.andExpect(jsonPath("$.selectedResponseName").value(nullValue()));
		detail(shipmentId)
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("DISMISSED"))
				.andExpect(jsonPath("$.recommendationDecision.decidedByName").value("Linked Importer"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"));
		detail(importerToken, shipmentId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("DISMISSED"));
		assertThat(decisionCount(shipmentId)).isEqualTo(1);
		assertThat(mailCount(shipmentId)).isZero();
	}

	@Test
	void aRejectedFollowUpKeepsTheStoredDecisionAndALaterOneUsesTheRecommendationThen() throws Exception {
		impacts.use(ShipmentRecommendationImpact.noPredictedImpact());
		long shipmentId = createShipment();
		decide(shipmentId, "{\"outcome\":\"APPROVED\"}").andExpect(status().isOk());

		decide(shipmentId, "{\"outcome\":\"MODIFIED\",\"selectedResponse\":\"NO_ACTION\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.selectedResponse").value("A modification selects a different response"));
		detail(shipmentId)
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationDecision.selectedResponseName").value(nullValue()));

		impacts.use(ShipmentRecommendationImpact.unavailable("The berth is closed."));
		decide(shipmentId, "{\"outcome\":\"DISMISSED\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(RecommendationDecisionRejectedException.UNAVAILABLE));
		detail(shipmentId)
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"));

		impacts.use(ShipmentRecommendationImpact.affectsConstraints());
		decide(shipmentId, "{\"outcome\":\"APPROVED\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationName").value("Monitor"))
				.andExpect(jsonPath("$.recommendationWording").value(MONITOR))
				.andExpect(jsonPath("$.selectedResponseName").value(nullValue()));
		decide(shipmentId, "{\"outcome\":\"MODIFIED\",\"selectedResponse\":\"NO_ACTION\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationName").value("Monitor"))
				.andExpect(jsonPath("$.selectedResponseName").value("No action"))
				.andExpect(jsonPath("$.selectedResponseWording").value(NO_ACTION));
		decide(shipmentId, "{\"outcome\":\"MODIFIED\",\"selectedResponse\":\"MONITOR_CONSTRAINTS\"}")
				.andExpect(status().isBadRequest());

		detail(shipmentId)
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("MODIFIED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("Monitor"))
				.andExpect(jsonPath("$.recommendationDecision.selectedResponseName").value("No action"))
				.andExpect(jsonPath("$.risk.level").value("LOW"));
		assertThat(decisionCount(shipmentId)).isEqualTo(1);
		assertThat(mailCount(shipmentId)).isZero();
	}

	@Test
	void aHighRiskApprovalStoresThatResponseRatherThanTheRiskExplanation() throws Exception {
		impacts.use(ShipmentRecommendationImpact.noPredictedImpact());
		long shipmentId = createShipment("2026-10-15T08:00:00+08:00", "2026-10-15T18:00:00+08:00");
		String watch = ShipmentRecommendedResponse.Code.WATCH_CONNECTION.wording();

		decide(shipmentId, "{\"outcome\":\"APPROVED\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationName").value("Watch the connection"))
				.andExpect(jsonPath("$.recommendationWording").value(watch));
		detail(shipmentId)
				.andExpect(jsonPath("$.risk.level").value("HIGH"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationWording").value(watch))
				.andExpect(jsonPath("$.risk.explanation", org.hamcrest.Matchers.containsString("planned connection window")));
		assertThat(watch).doesNotContain("planned connection window");
		assertThat(decisionCount(shipmentId)).isEqualTo(1);
		assertThat(mailCount(shipmentId)).isZero();
	}

	@Test
	void anArchivedShipmentWithNoDecisionAndAnUnavailableRecommendationStoresNothing() throws Exception {
		long shipmentId = createShipment();
		Shipment shipment = shipments.findById(shipmentId).orElseThrow();
		shipment.archive();
		shipments.saveAndFlush(shipment);

		decide(shipmentId, "{\"outcome\":\"APPROVED\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(RecommendationDecisionRejectedException.ARCHIVED));

		impacts.use(ShipmentRecommendationImpact.noPredictedImpact());
		decide(shipmentId, "{\"outcome\":\"DISMISSED\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(RecommendationDecisionRejectedException.ARCHIVED));

		detail(shipmentId)
				.andExpect(jsonPath("$.status").value("ARCHIVED"))
				.andExpect(jsonPath("$.recommendationDecision").value(nullValue()));
		assertThat(decisionCount(shipmentId)).isZero();
		assertThat(mailCount(shipmentId)).isZero();
	}

	@Test
	void theStoredDecisionSurvivesShipmentChangesAndStaysOnThatShipment() throws Exception {
		impacts.use(ShipmentRecommendationImpact.noPredictedImpact());
		long shipmentId = createShipment();
		long otherShipmentId = createShipment();
		decide(shipmentId, """
				{"outcome":"APPROVED","selectedResponse":null,"decidedAt":"1999-01-01T00:00:00Z","recommendationWording":"Call the terminal."}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationWording").value(NO_ACTION))
				.andExpect(jsonPath("$.decidedAt").value(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("1999"))));

		String reference = jdbc.queryForObject("SELECT shipment_reference FROM shipments WHERE id = ?", String.class,
				shipmentId);
		long version = jdbc.queryForObject("SELECT version FROM shipments WHERE id = ?", Long.class, shipmentId);
		mvc.perform(put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-15T18:00:00+08:00",
						  "version": %d
						}
						""".formatted(reference, version)))
				.andExpect(status().isOk());

		detail(shipmentId)
				.andExpect(jsonPath("$.risk.level").value("HIGH"))
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationWording").value(NO_ACTION));

		Long importerId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, "STRAITS_FRESH_DEMO");
		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"importerCompanyId\":" + importerId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"));
		assertThat(decisionCount(shipmentId)).isEqualTo(1);

		Company importer = companies.findByCode("STRAITS_FRESH_DEMO").orElseThrow();
		AccountRole importerRole = roles.findByCode(Role.IMPORTER).orElseThrow();
		users.saveAndFlush(new UserAccount("Importer User", "decision-importer@example.test",
				passwords.encode("Example123"), importerRole, importer));
		String importerToken = tokenFor("decision-importer@example.test");
		detail(importerToken, shipmentId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"))
				.andExpect(jsonPath("$.importer.id").value(importerId));

		AccountRole forwarder = roles.findByCode(Role.FREIGHT_FORWARDER).orElseThrow();
		Company owner = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		users.saveAndFlush(new UserAccount("Other User", "decision-other@example.test",
				passwords.encode("Example123"), forwarder, owner));
		String otherToken = tokenFor("decision-other@example.test");
		String watch = ShipmentRecommendedResponse.Code.WATCH_CONNECTION.wording();
		decide(otherToken, shipmentId, "{\"outcome\":\"DISMISSED\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.decidedByName").value("Other User"))
				.andExpect(jsonPath("$.outcome").value("DISMISSED"))
				.andExpect(jsonPath("$.recommendationName").value("Watch the connection"))
				.andExpect(jsonPath("$.recommendationWording").value(watch))
				.andExpect(jsonPath("$.selectedResponseName").value(nullValue()));

		detail(shipmentId)
				.andExpect(jsonPath("$.recommendationDecision.decidedByName").value("Other User"))
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("DISMISSED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("Watch the connection"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationWording").value(watch));
		detail(otherShipmentId)
				.andExpect(jsonPath("$.recommendationDecision").value(nullValue()));
		mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == " + shipmentId + ")].recommendationDecision").doesNotExist());

		assertThat(decisionCount(shipmentId)).isEqualTo(1);
		assertThat(decisionCount(otherShipmentId)).isZero();
		assertThat(mailCount(shipmentId)).isZero();
	}

	private ResultActions decide(long shipmentId, String body) throws Exception {
		return decide(token, shipmentId, body);
	}

	private ResultActions decide(String bearer, long shipmentId, String body) throws Exception {
		return mvc.perform(patch("/api/shipments/" + shipmentId + "/recommendation-decision")
				.header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions detail(long shipmentId) throws Exception {
		return detail(token, shipmentId);
	}

	private ResultActions detail(String bearer, long shipmentId) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + bearer));
	}

	private long createShipment() throws Exception {
		return createShipment("2026-10-15T08:00:00+08:00", "2026-10-16T12:00:00+08:00");
	}

	private long createShipment(String motherArrival, String feederDeparture) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "%s",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "%s"
						}
						""".formatted("DEC-" + UUID.randomUUID(), motherArrival, feederDeparture)))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private Map<String, Object> shipmentRow(long shipmentId) {
		return jdbc.queryForMap("""
				SELECT status, version, importer_company_id, planned_mother_arrival_at, planned_feeder_departure_at,
					updated_at, updated_by_user_id
				FROM shipments WHERE id = ?
				""", shipmentId);
	}

	private int decisionCount(long shipmentId) {
		return countWhere("recommendation_decisions", shipmentId);
	}

	private int historyCount(long shipmentId) {
		return countWhere("shipment_change_events", shipmentId);
	}

	private int riskCount(long shipmentId) {
		return countWhere("shipment_risk_states", shipmentId) + countWhere("shipment_risk_transitions", shipmentId);
	}

	private int mailCount(long shipmentId) {
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM shipment_risk_alert_deliveries delivery
				JOIN shipment_risk_transitions transition ON transition.id = delivery.transition_id
				WHERE transition.shipment_id = ?
				""", Integer.class, shipmentId);
		return count == null ? 0 : count;
	}

	private int countWhere(String table, long shipmentId) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE shipment_id = ?", Integer.class,
				shipmentId);
		return count == null ? 0 : count;
	}

	private int count(String table) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		return count == null ? 0 : count;
	}

	static final class ChosenImpact extends ShipmentRecommendationImpactSource {
		private ShipmentRecommendationImpact impact = ShipmentRecommendationImpact.notReturned();

		@Override
		ShipmentRecommendationImpact current() {
			return impact;
		}

		void use(ShipmentRecommendationImpact impact) {
			this.impact = impact;
		}
	}

	@TestConfiguration
	static class ImpactConfiguration {
		@Bean
		@Primary
		ChosenImpact chosenImpact() {
			return new ChosenImpact();
		}
	}
}
