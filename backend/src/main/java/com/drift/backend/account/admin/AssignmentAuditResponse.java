package com.drift.backend.account.admin;

import java.time.Instant;

import com.drift.backend.account.Role;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AssignmentAudit", description = "One saved change of a user's organisation or role.")
public record AssignmentAuditResponse(
		@Schema(example = "4") Long id,
		@Schema(example = "2026-10-10T08:22:00Z") Instant recordedAt,
		AssignmentAuditParty operator,
		AssignmentAuditParty target,
		@Schema(example = "FREIGHT_FORWARDER") Role previousRole,
		@Schema(example = "LOGISTICS_MANAGER") Role assignedRole,
		@Schema(description = "Organisation before the change. Null when the user had none.", nullable = true)
		AdminOrganisationResponse previousOrganisation,
		AdminOrganisationResponse organisation) {

	static AssignmentAuditResponse from(UserAssignmentAudit audit) {
		return new AssignmentAuditResponse(audit.getId(), audit.getRecordedAt(),
				AssignmentAuditParty.from(audit.getOperator()), AssignmentAuditParty.from(audit.getTarget()),
				audit.getPreviousRole(), audit.getAssignedRole(), previousOrganisation(audit),
				new AdminOrganisationResponse(audit.getOrganisationId(), audit.getOrganisationCode(), audit.getOrganisationName()));
	}

	private static AdminOrganisationResponse previousOrganisation(UserAssignmentAudit audit) {
		if (audit.getPreviousOrganisationId() == null) {
			return null;
		}
		return new AdminOrganisationResponse(audit.getPreviousOrganisationId(), audit.getPreviousOrganisationCode(),
				audit.getPreviousOrganisationName());
	}
}
