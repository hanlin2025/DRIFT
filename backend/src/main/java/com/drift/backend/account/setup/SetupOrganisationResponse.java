package com.drift.backend.account.setup;

import com.drift.backend.company.Company;

public record SetupOrganisationResponse(Long id, String code, String name) {

	public static SetupOrganisationResponse from(Company company) {
		return new SetupOrganisationResponse(company.getId(), company.getCode(), company.getName());
	}
}
