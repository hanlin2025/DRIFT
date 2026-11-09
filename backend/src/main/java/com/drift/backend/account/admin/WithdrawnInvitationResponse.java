package com.drift.backend.account.admin;

import java.time.Instant;

import com.drift.backend.account.Role;
import com.drift.backend.account.registration.invitation.Invitation;
import com.drift.backend.account.registration.invitation.InvitationStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "WithdrawnInvitation", description = "A pending invitation after an administrator withdrew it. It does not include the invitation token.")
public record WithdrawnInvitationResponse(
		@Schema(example = "15") Long id,
		@Schema(example = "colleague@example.com") String email,
		@Schema(example = "FREIGHT_FORWARDER") Role role,
		AdminOrganisationResponse organisation,
		@Schema(example = "REVOKED") InvitationStatus status,
		Instant withdrawnAt) {

	static WithdrawnInvitationResponse from(Invitation invitation, Instant withdrawnAt) {
		return new WithdrawnInvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole(),
				AdminOrganisationResponse.from(invitation.getCompany()), invitation.getStatus(), withdrawnAt);
	}
}
