package com.drift.backend.account.admin;

import com.drift.backend.account.UserAccount;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Assignment", description = "Result of assigning an organisation and role to a user.")
public record AssignmentResponse(
		@Schema(example = "User assigned successfully") String message,
		AdminUserResponse user) {

	public static final String MESSAGE = "User assigned successfully";

	static AssignmentResponse from(UserAccount account) {
		return new AssignmentResponse(MESSAGE, AdminUserResponse.from(account));
	}
}
