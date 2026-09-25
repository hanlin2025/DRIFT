package com.drift.backend.registration.account;

import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotBlank(message = "Full name is required")
		@Size(max = 200, message = "Full name must be at most 200 characters")
		String fullName,

		@NotBlank(message = "Email is required")
		@Email(message = "Email must be a valid email address")
		@Size(max = 320, message = "Email must be at most 320 characters")
		String email,

		@NotBlank(message = "Password is required")
		String password) {

	public RegisterRequest {
		fullName = fullName == null ? null : fullName.trim();
		email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return "RegisterRequest[fullName=" + fullName + ", email=" + email + "]";
	}
}
