package com.drift.backend.shipment;

import com.drift.backend.company.Company;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Importer organisation linked to a shipment. Null when the shipment is not linked.")
public record ImporterOrganisation(
		@Schema(example = "2")
		Long id,
		@Schema(example = "STRAITS_FRESH_DEMO")
		String code,
		@Schema(example = "Straits Fresh Imports (Demo)")
		String name) {

	static ImporterOrganisation of(Company company) {
		if (company == null) {
			return null;
		}
		return new ImporterOrganisation(company.getId(), company.getCode(), company.getName());
	}
}
