package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.drift.backend.company.Company;

class ShipmentAccessPolicyTest {

	private final ShipmentAccessPolicy policy = new ShipmentAccessPolicy();

	@Test
	void allowsTheManagingCompanyAndTheLinkedImporter() {
		assertThat(policy.canView(10L, 10L, null)).isTrue();
		assertThat(policy.canView(10L, 10L, 20L)).isTrue();
		assertThat(policy.canView(20L, 10L, 20L)).isTrue();
		assertThat(policy.canView(30L, 10L, 20L)).isFalse();
		assertThat(policy.canView(30L, 10L, null)).isFalse();
	}

	@Test
	void entityRuleUsesTheSameCompanyIds() {
		Company managing = company(10L);
		Company importer = company(20L);
		Company other = company(30L);
		Shipment shipment = mock(Shipment.class);
		when(shipment.getCompany()).thenReturn(managing);
		when(shipment.getImporterCompany()).thenReturn(importer);

		assertThat(policy.canView(managing, shipment)).isTrue();
		assertThat(policy.canView(importer, shipment)).isTrue();
		assertThat(policy.canView(other, shipment)).isFalse();

		when(shipment.getImporterCompany()).thenReturn(null);
		assertThat(policy.canView(managing, shipment)).isTrue();
		assertThat(policy.canView(importer, shipment)).isFalse();
	}

	private static Company company(Long id) {
		Company company = mock(Company.class);
		when(company.getId()).thenReturn(id);
		return company;
	}
}
