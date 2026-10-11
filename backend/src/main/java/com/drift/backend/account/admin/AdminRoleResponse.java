package com.drift.backend.account.admin;

import com.drift.backend.account.Role;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AdminRole", description = "A role an administrator can assign.")
public record AdminRoleResponse(
		@Schema(example = "LOGISTICS_MANAGER") Role code,
		@Schema(example = "Logistics manager") String label) {
}
