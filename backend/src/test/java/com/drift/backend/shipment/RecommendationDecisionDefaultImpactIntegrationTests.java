package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RecommendationDecisionDefaultImpactIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired CompanyRepository companies;
	@Autowired AccountRoleRepository roles;
	@Autowired UserAccountRepository users;

	private String token;

	@BeforeEach
	void account() throws Exception {
		Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = roles.findByCode(Role.FREIGHT_FORWARDER).orElseThrow();
		users.saveAndFlush(new UserAccount("Default Impact User", "decision-default-impact@example.test",
				passwords.encode("Example123"), role, company));
		token = tokenFor("decision-default-impact@example.test");
	}

	@Test
	void theNormalImpactSourceRecordsAnApproval() throws Exception {
		MvcResult created = mvc.perform(post("/api/shipments")
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
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
						}
						""".formatted("DEC-" + UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andReturn();
		long shipmentId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
		String noAction = ShipmentRecommendedResponse.Code.NO_ACTION.wording();

		mvc.perform(patch("/api/shipments/" + shipmentId + "/recommendation-decision")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"outcome\":\"APPROVED\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationWording").value(noAction));

		mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendationDecision.outcome").value("APPROVED"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationName").value("No action"))
				.andExpect(jsonPath("$.recommendationDecision.recommendationWording").value(noAction));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_decisions WHERE shipment_id = ?",
				Integer.class, shipmentId)).isEqualTo(1);
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}
}
