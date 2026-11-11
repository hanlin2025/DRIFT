package com.drift.backend.account.signup;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(name = "VerifyEmailRequest")
public record VerifyEmailRequest(
		@Schema(description = "The 43-character token from the #token= fragment of the emailed link.")
		@NotBlank(message = InvalidVerificationTokenException.MESSAGE)
		@Pattern(regexp = "[A-Za-z0-9_-]{43}", message = InvalidVerificationTokenException.MESSAGE) String token) {

	@Override
	public String toString() {
		return "VerifyEmailRequest[redacted]";
	}
}
