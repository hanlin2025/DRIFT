package com.drift.backend.registration.invitation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record InvitationRequest(
		@NotBlank(message = "An invitation is required")
		@Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "Invitation is invalid or unavailable") String token) {
	@Override
	public String toString() { return "InvitationRequest[redacted]"; }
}
