package com.drift.backend.account.authentication;

import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
		@NotBlank(message = "Email is required")
		@Email(message = "Email must be a valid email address")
		@Size(max = 320, message = "Email must be at most 320 characters") String email,
		@NotBlank(message = "Password is required") String password) {

	public LoginRequest {
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return "LoginRequest[redacted]";
	}
}
