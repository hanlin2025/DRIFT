package com.drift.backend.account.admin;

import java.time.Instant;

import com.drift.backend.account.Role;
import com.drift.backend.account.registration.invitation.Invitation;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ManagedInvitation", description = "A pending invitation an administrator may manage. It never includes the invitation token.")
public record ManagedInvitationResponse(
		@Schema(example = "15") Long id,
		@Schema(example = "colleague@example.com") String email,
		@Schema(example = "FREIGHT_FORWARDER") Role role,
		AdminOrganisationResponse organisation,
		Instant expiresAt,
		@Schema(description = "True when the invitation is still pending but its expiry has passed.") boolean expired) {

	public static ManagedInvitationResponse from(Invitation invitation, Instant now) {
		return new ManagedInvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole(),
				AdminOrganisationResponse.from(invitation.getCompany()), invitation.getExpiresAt(),
				!invitation.getExpiresAt().isAfter(now));
	}
}
