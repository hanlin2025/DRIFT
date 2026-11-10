package com.drift.backend.account.signup;

import java.util.Locale;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "ResendVerificationRequest")
public record ResendVerificationRequest(
		@Schema(example = "alex@pacificgate.example")
		@NotBlank(message = "Email is required")
		@Email(message = "Email must be a valid email address")
		@Size(max = 320, message = "Email must be at most 320 characters") String email) {

	public ResendVerificationRequest {
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return "ResendVerificationRequest[redacted]";
	}
}
