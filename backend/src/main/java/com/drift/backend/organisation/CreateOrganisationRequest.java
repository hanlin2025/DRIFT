package com.drift.backend.organisation;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CreateOrganisationRequest", description = "The identity of an organisation to add. The code is stored in uppercase.")
public record CreateOrganisationRequest(
		@Schema(example = "PACIFIC_GATE", description = "Letters, digits, and underscores. The first character is a letter. Stored in uppercase.")
		String code,
		@Schema(example = "Pacific Gate Imports", description = "Display name, up to 200 characters.")
		String name) {
}
