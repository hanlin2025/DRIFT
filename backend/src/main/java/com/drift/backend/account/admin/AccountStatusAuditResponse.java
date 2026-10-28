package com.drift.backend.account.admin;

import java.time.Instant;

import com.drift.backend.account.AccountStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AccountStatusAudit", description = "One saved change of a user's account status.")
public record AccountStatusAuditResponse(
		@Schema(example = "3") Long id,
		@Schema(example = "2026-10-10T08:22:00Z") Instant recordedAt,
		AssignmentAuditParty operator,
		AssignmentAuditParty target,
		@Schema(description = "Organisation the change was made in.", example = "1") Long organisationId,
		@Schema(example = "ACTIVE") AccountStatus previousStatus,
		@Schema(example = "REMOVED") AccountStatus newStatus) {

	static AccountStatusAuditResponse from(AccountStatusAudit audit) {
		return new AccountStatusAuditResponse(audit.getId(), audit.getRecordedAt(),
				AssignmentAuditParty.from(audit.getOperator()), AssignmentAuditParty.from(audit.getTarget()),
				audit.getOrganisationId(), audit.getPreviousStatus(), audit.getNewStatus());
	}
}
