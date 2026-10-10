package com.drift.backend.account.admin;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Organisation and role to assign to a user.")
public record AssignUserRequest(
		@Schema(description = "Active organisation to assign. Organisations are companies.", example = "2")
		Long organisationId,
		@Schema(description = "Role code stored for the user.", example = "LOGISTICS_MANAGER")
		String role) {
}
