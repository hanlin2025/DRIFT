package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.drift.backend.account.Role;

class ShipmentRiskAlertEmailComposerTest {

	@Test
	void buildsAbsoluteRoleSpecificShipmentLinks() {
		assertThat(ShipmentRiskAlertEmailComposer.shipmentLink("https://app.example.test/", Role.FREIGHT_FORWARDER, 42L))
				.isEqualTo("https://app.example.test/freight-forwarder/shipments/42");
		assertThat(ShipmentRiskAlertEmailComposer.shipmentLink("https://app.example.test", Role.IMPORTER, 42L))
				.isEqualTo("https://app.example.test/importer/shipments/42");
		assertThat(ShipmentRiskAlertEmailComposer.shipmentLink("https://app.example.test", Role.ADMIN, 42L))
				.isEqualTo("https://app.example.test/admin/shipments/42");
		assertThat(ShipmentRiskAlertEmailComposer.shipmentLink("https://app.example.test", Role.LOGISTICS_MANAGER, 42L))
				.isEqualTo("https://app.example.test/logistics-manager/shipments/42");
	}
}
