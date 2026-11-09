package com.drift.backend.account.admin;

import java.time.Instant;

import com.drift.backend.account.Role;
import com.drift.backend.account.registration.invitation.Invitation;

public record InvitationIssuedResponse(
		String email,
		Role role,
		AdminOrganisationResponse organisation,
		Instant expiresAt) {

	public static InvitationIssuedResponse from(Invitation invitation) {
		return new InvitationIssuedResponse(invitation.getEmail(), invitation.getRole(),
				AdminOrganisationResponse.from(invitation.getCompany()), invitation.getExpiresAt());
	}
}
