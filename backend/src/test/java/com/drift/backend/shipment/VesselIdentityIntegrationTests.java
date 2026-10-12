package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VesselIdentityIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired LatestAisPositions latestPositions;

	private String token;

	@BeforeEach
	void account() throws Exception {
		String email = "cdg144-" + UUID.randomUUID() + "@example.com";
		Long companyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, "HARBOURLINE_DEMO");
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Alice Tan', ?, ?, id, ?
				FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", email, passwords.encode("Example123"), companyId);
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");
		latestPositions.clear();
	}

	@Test
	void storesOptionalVesselIdentityAndStillLoadsAShipmentWithoutIt() throws Exception {
		latestPositions.record(position("563123456", "MV Pacific Horizon"));

		long withoutIdentity = create(shipment("HBL-144-PLAIN", ""));
		assertThat(identity(withoutIdentity))
				.containsEntry("mother_vessel_imo", null)
				.containsEntry("mother_vessel_mmsi", null)
				.containsEntry("mother_vessel_operator", null)
				.containsEntry("feeder_vessel_imo", null)
				.containsEntry("feeder_vessel_mmsi", null)
				.containsEntry("feeder_vessel_operator", null);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/shipments/" + withoutIdentity)
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.motherVessel").value("MV Pacific Horizon"))
				.andExpect(jsonPath("$.plannedMotherArrivalAt").exists())
				.andExpect(jsonPath("$.plannedFeederDepartureAt").exists());

		long withIdentity = create(shipment("HBL-144-IDENTITY", """
				, "motherVesselImo": " 9387421 ",
				  "motherVesselMmsi": "563000111",
				  "motherVesselOperator": " Harbourline ",
				  "feederVesselImo": "9411882",
				  "feederVesselMmsi": "565987654",
				  "feederVesselOperator": "Strait Feeders"
				"""));
		assertThat(identity(withIdentity))
				.containsEntry("mother_vessel", "MV Pacific Horizon")
				.containsEntry("mother_vessel_imo", "9387421")
				.containsEntry("mother_vessel_mmsi", "563000111")
				.containsEntry("mother_vessel_operator", "Harbourline")
				.containsEntry("feeder_vessel_imo", "9411882")
				.containsEntry("feeder_vessel_mmsi", "565987654")
				.containsEntry("feeder_vessel_operator", "Strait Feeders");
	}

	@Test
	void rejectsMalformedIdentityAndLeavesTheShipmentUnchanged() throws Exception {
		int before = jdbc.queryForObject("SELECT COUNT(*) FROM shipments", Integer.class);
		mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(shipment("HBL-144-BAD", """
						, "motherVesselImo": "IMO9387421",
						  "feederVesselMmsi": "123"
						""")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.motherVesselImo").value("Mother vessel IMO must be 7 digits."))
				.andExpect(jsonPath("$.errors.feederVesselMmsi").value("Feeder vessel MMSI must be 9 digits."));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments", Integer.class)).isEqualTo(before);
	}

	@Test
	void rejectsAnOperatorLongerThanTheColumnAndKeepsATwoHundredCharacterOperator() throws Exception {
		String operator = "H".repeat(200);
		long id = create(shipment("HBL-144-OPERATOR", """
				, "motherVesselOperator": "%s"
				""".formatted(operator)));
		assertThat(identity(id)).containsEntry("mother_vessel_operator", operator);

		mvc.perform(put("/api/shipments/" + id)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "HBL-144-OPERATOR",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00",
						  "motherVesselOperator": "%s",
						  "feederVesselOperator": "%s",
						  "version": 0
						}
						""".formatted("F".repeat(201), "S".repeat(201))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.motherVesselOperator").value("Mother vessel operator must be at most 200 characters."))
				.andExpect(jsonPath("$.errors.feederVesselOperator").value("Feeder vessel operator must be at most 200 characters."));
		assertThat(identity(id)).containsEntry("mother_vessel_operator", operator);
	}

	@Test
	void anUpdateThatOmitsIdentityLeavesTheStoredValuesInPlace() throws Exception {
		long id = create(shipment("HBL-144-KEEP", """
				, "motherVesselImo": "9387421",
				  "motherVesselMmsi": "563000111",
				  "motherVesselOperator": "Harbourline",
				  "feederVesselImo": "9411882",
				  "feederVesselMmsi": "565987654",
				  "feederVesselOperator": "Strait Feeders"
				"""));
		mvc.perform(put("/api/shipments/" + id)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "HBL-144-KEPT",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00",
						  "motherVesselImo": " 9390001 ",
						  "version": 0
						}
						"""))
				.andExpect(status().isOk());
		assertThat(identity(id))
				.containsEntry("mother_vessel", "MV Pacific Horizon")
				.containsEntry("mother_vessel_imo", "9390001")
				.containsEntry("mother_vessel_mmsi", "563000111")
				.containsEntry("mother_vessel_operator", "Harbourline")
				.containsEntry("feeder_vessel_imo", "9411882")
				.containsEntry("feeder_vessel_mmsi", "565987654")
				.containsEntry("feeder_vessel_operator", "Strait Feeders");
		assertThat(jdbc.queryForObject("SELECT shipment_reference FROM shipments WHERE id = ?", String.class, id))
				.isEqualTo("HBL-144-KEPT");
	}

	@Test
	void blankIdentityClearsStoredValuesOnUpdate() throws Exception {
		long id = create(shipment("HBL-144-CLEAR", """
				, "motherVesselImo": "9387421",
				  "motherVesselMmsi": "563000111",
				  "motherVesselOperator": "Harbourline",
				  "feederVesselImo": "9411882",
				  "feederVesselMmsi": "565987654",
				  "feederVesselOperator": "Strait Feeders"
				"""));
		String body = """
				{
				  "shipmentReference": "HBL-144-CLEAR",
				  "origin": "Shanghai, CN",
				  "destination": "Jakarta, ID",
				  "transshipmentPort": "Singapore",
				  "motherVessel": "MV Pacific Horizon",
				  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
				  "feederVessel": "MV Strait Runner",
				  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00",
				  "motherVesselImo": "   ",
				  "motherVesselMmsi": "",
				  "motherVesselOperator": null,
				  "version": 0
				}
				""";
		mvc.perform(put("/api/shipments/" + id)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk());
		assertThat(identity(id))
				.containsEntry("mother_vessel_imo", null)
				.containsEntry("mother_vessel_mmsi", null)
				.containsEntry("mother_vessel_operator", null)
				.containsEntry("feeder_vessel_imo", "9411882")
				.containsEntry("feeder_vessel_mmsi", "565987654")
				.containsEntry("feeder_vessel_operator", "Strait Feeders");
		assertThat(((java.sql.Timestamp) identity(id).get("planned_mother_arrival_at")).toInstant())
				.isEqualTo(java.time.Instant.parse("2026-10-15T00:00:00Z"));
	}

	private java.util.Map<String, Object> identity(long id) {
		return jdbc.queryForMap("""
				SELECT mother_vessel, mother_vessel_imo, mother_vessel_mmsi, mother_vessel_operator,
					feeder_vessel_imo, feeder_vessel_mmsi, feeder_vessel_operator, planned_mother_arrival_at
				FROM shipments WHERE id = ?
				""", id);
	}

	private long create(String body) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private static String shipment(String reference, String identity) {
		return """
				{
				  "shipmentReference": "%s",
				  "origin": "Shanghai, CN",
				  "destination": "Jakarta, ID",
				  "transshipmentPort": "Singapore",
				  "motherVessel": "MV Pacific Horizon",
				  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
				  "feederVessel": "MV Strait Runner",
				  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"%s
				}
				""".formatted(reference, identity);
	}

	private static AisPosition position(String mmsi, String name) {
		return new AisPosition(mmsi, name, new BigDecimal("1.264"), new BigDecimal("103.82"),
				new BigDecimal("12.4"), new BigDecimal("175.0"), 176, 0, true, 42,
				Instant.parse("2026-10-05T03:00:00Z"), AisPositionSource.AIS_STREAM);
	}
}
