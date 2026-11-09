package com.drift.backend.account.admin;

import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InviteColleagueRequest(
		@NotNull(message = "Choose an active organisation.") Long organisationId,
		@NotBlank(message = "Choose a role.") String role,
		@NotBlank(message = "Email is required")
		@Email(message = "Email must be a valid email address")
		@Size(max = 320, message = "Email must be at most 320 characters") String email) {

	public InviteColleagueRequest {
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
		role = role == null ? null : role.strip();
	}

	@Override
	public String toString() {
		return "InviteColleagueRequest[redacted]";
	}
}
