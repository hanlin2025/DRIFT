package com.drift.backend.account.authentication;

import com.drift.backend.company.Company;

public record CompanySummary(Long id, String code, String name) {

	public static CompanySummary of(Company company) {
		if (company == null) {
			return null;
		}
		return new CompanySummary(company.getId(), company.getCode(), company.getName());
	}
}
