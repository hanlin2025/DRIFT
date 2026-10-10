package com.drift.backend.account.admin;

import com.drift.backend.company.Company;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AdminOrganisation", description = "An organisation an administrator can assign to a user.")
public record AdminOrganisationResponse(
		@Schema(example = "2") Long id,
		@Schema(example = "STRAITS_FRESH_DEMO") String code,
		@Schema(example = "Straits Fresh Imports (Demo)") String name) {

	static AdminOrganisationResponse from(Company company) {
		if (company == null) {
			return null;
		}
		return new AdminOrganisationResponse(company.getId(), company.getCode(), company.getName());
	}
}
