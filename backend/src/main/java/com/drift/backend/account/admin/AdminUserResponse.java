package com.drift.backend.account.admin;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AdminUser", description = "A user an administrator can assign to an organisation and role.")
public record AdminUserResponse(
		@Schema(example = "15") Long id,
		@Schema(example = "Alice Tan") String fullName,
		@Schema(example = "alice@example.com") String email,
		@Schema(example = "FREIGHT_FORWARDER") Role role,
		@Schema(description = "Organisation currently assigned to the user. Null when the user has none.", nullable = true)
		AdminOrganisationResponse organisation) {

	static AdminUserResponse from(UserAccount account) {
		return new AdminUserResponse(account.getId(), account.getFullName(), account.getEmail(), account.getRole(),
				AdminOrganisationResponse.from(account.getCompany()));
	}
}
