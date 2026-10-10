package com.drift.backend.account.admin;

import com.drift.backend.account.UserAccount;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AssignmentAuditParty", description = "The administrator who made an assignment, or the user who received it.")
public record AssignmentAuditParty(
		@Schema(example = "9") Long id,
		@Schema(example = "Amina Rahman") String fullName,
		@Schema(example = "amina@example.com") String email) {

	static AssignmentAuditParty from(UserAccount account) {
		return new AssignmentAuditParty(account.getId(), account.getFullName(), account.getEmail());
	}
}
