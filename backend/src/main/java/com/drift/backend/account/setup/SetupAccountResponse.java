package com.drift.backend.account.setup;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;

public record SetupAccountResponse(
		Long id,
		String fullName,
		String email,
		Role role,
		SetupOrganisationResponse organisation) {

	public static SetupAccountResponse from(UserAccount account) {
		return new SetupAccountResponse(account.getId(), account.getFullName(), account.getEmail(), account.getRole(),
				SetupOrganisationResponse.from(account.getCompany()));
	}
}
