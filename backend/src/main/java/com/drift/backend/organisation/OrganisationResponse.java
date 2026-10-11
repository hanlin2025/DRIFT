package com.drift.backend.organisation;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Organisation", description = "An active company that can be selected as an importer organisation.")
public record OrganisationResponse(
		Long id,
		String code,
		String name) {
}
