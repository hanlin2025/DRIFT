package com.drift.backend.account.setup;

import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SetupAdministratorRequest(
		@NotNull(message = "Choose an active organisation.") Long organisationId,
		@NotBlank(message = "Full name is required")
		@Size(max = 200, message = "Full name must be at most 200 characters") String fullName,
		@NotBlank(message = "Email is required")
		@Email(message = "Email must be a valid email address")
		@Size(max = 320, message = "Email must be at most 320 characters") String email,
		@NotBlank(message = "Password is required")
		@Size(max = 72, message = "Password must be at most 72 UTF-8 bytes") String password) {

	public SetupAdministratorRequest {
		fullName = fullName == null ? null : fullName.strip();
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return "SetupAdministratorRequest[redacted]";
	}
}
