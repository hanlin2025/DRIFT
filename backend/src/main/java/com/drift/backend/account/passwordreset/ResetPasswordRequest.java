package com.drift.backend.account.passwordreset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
		@NotBlank(message = "Reset link is required")
		@Pattern(regexp = "[A-Za-z0-9_-]{43}", message = InvalidResetTokenException.MESSAGE) String token,
		@NotBlank(message = "Password is required")
		@Size(max = 72, message = "Password must be at most 72 UTF-8 bytes") String password) {

	@Override
	public String toString() {
		return "ResetPasswordRequest[redacted]";
	}
}
