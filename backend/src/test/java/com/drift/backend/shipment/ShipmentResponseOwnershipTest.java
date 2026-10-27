package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ShipmentResponseOwnershipTest {

	private final ObjectMapper json = JsonMapper.builder().build();

	@Test
	void listAndDetailReturnTheOwningCompanyIdWhenAnImporterIsLinked() throws Exception {
		Company owner = company(11L, "HARBOURLINE_DEMO", "Harbourline Logistics (Demo)");
		Company importer = company(22L, "STRAITS_FRESH_DEMO", "Straits Fresh Imports (Demo)");
		Shipment shipment = shipment(owner);
		shipment.linkImporter(importer);

		ShipmentResponse listed = ShipmentResponse.from(shipment);
		ShipmentDetailResponse detail = detail(shipment);

		assertThat(listed.companyId()).isEqualTo(11L);
		assertThat(listed.status()).isEqualTo(ShipmentStatus.ACTIVE);
		assertThat(listed.updatedByName()).isEqualTo("Ada");
		assertThat(listed.updatedAt()).isEqualTo(shipment.getUpdatedAt());
		assertThat(detail.companyId()).isEqualTo(11L);
		assertThat(detail.status()).isEqualTo(ShipmentStatus.ACTIVE);
		assertThat(detail.importer().id()).isEqualTo(22L);
		assertThat(detail.remainingConnectionWindow()).isNull();
		assertThat(detail.risk().level()).isEqualTo(ShipmentRisk.Level.LOW);
		assertThat(detail.risk().explanation()).isEqualTo(
				"At least 24 hours to transfer cargo between vessels. The planned connection window is being used.");

		JsonNode listedJson = json.valueToTree(listed);
		JsonNode detailJson = json.valueToTree(detail);
		assertThat(listedJson.get("companyId").asLong()).isEqualTo(11L);
		assertThat(listedJson.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(listedJson.has("risk")).isFalse();
		assertThat(listedJson.has("remainingConnectionWindow")).isFalse();
		assertThat(listedJson.has("importer")).isFalse();
		assertThat(detailJson.has("remainingConnectionWindow")).isTrue();
		assertThat(detailJson.get("remainingConnectionWindow").isNull()).isTrue();
		assertThat(listedJson.get("updatedByName").asString()).isEqualTo("Ada");
		assertThat(detailJson.has("updatedByName")).isFalse();
		assertThat(detailJson.has("updatedAt")).isFalse();
		assertThat(detailJson.get("companyId").asLong()).isEqualTo(11L);
		assertThat(detailJson.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(detailJson.get("risk").get("level").asString()).isEqualTo("LOW");
		assertThat(detailJson.get("importer").get("id").asLong()).isEqualTo(22L);
		assertThat(detailJson.get("companyId").asLong()).isNotEqualTo(detailJson.get("importer").get("id").asLong());
	}

	@Test
	void anUnlinkedShipmentStillReturnsItsOwningCompanyId() throws Exception {
		Shipment shipment = shipment(company(11L, "HARBOURLINE_DEMO", "Harbourline Logistics (Demo)"));

		JsonNode detailJson = json.valueToTree(detail(shipment));

		assertThat(ShipmentResponse.from(shipment).companyId()).isEqualTo(11L);
		assertThat(ShipmentResponse.from(shipment).status()).isEqualTo(ShipmentStatus.ACTIVE);
		assertThat(detailJson.get("companyId").asLong()).isEqualTo(11L);
		assertThat(detailJson.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(detailJson.get("risk").get("level").asString()).isEqualTo("LOW");
		assertThat(detailJson.get("importer").isNull()).isTrue();
		assertThat(detailJson.get("transshipmentArrivalDeadline").isNull()).isTrue();
	}

	private static ShipmentDetailResponse detail(Shipment shipment) {
		ConnectionWindowService windows = new ConnectionWindowService();
		ConnectionWindowCalculation planned = windows.calculate(shipment.getPlannedMotherArrivalAt(),
				shipment.getPlannedFeederDepartureAt());
		ArrivalEstimate estimate = ArrivalEstimate.unavailable("NO_POSITION",
				"No AIS position has been received for the mother vessel.");
		return ShipmentDetailResponse.from(shipment, planned,
				windows.remaining(shipment.getPlannedFeederDepartureAt(), estimate), null, null, estimate, null);
	}

	private static Shipment shipment(Company owner) {
		UserAccount creator = new UserAccount("Ada", "ada@example.com", "hash", new AccountRole(Role.FREIGHT_FORWARDER), owner);
		return new Shipment(owner, creator, "HBL-OWNER", "Singapore", "Jakarta", "Singapore", "Ever Steady",
				OffsetDateTime.parse("2026-10-02T00:00:00Z"), "Straits Feeder", OffsetDateTime.parse("2026-10-03T00:00:00Z"));
	}

	private static Company company(long id, String code, String name) throws Exception {
		var constructor = Company.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		Company company = constructor.newInstance();
		set(company, "id", id);
		set(company, "code", code);
		set(company, "name", name);
		set(company, "active", true);
		return company;
	}

	private static void set(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
