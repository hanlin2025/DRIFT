package com.drift.backend.organisation;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrganisationPageResponse(
		List<OrganisationResponse> items,
		@Schema(description = "Zero-based page index.", example = "0")
		int page,
		@Schema(description = "Requested page size. Defaults to 20.", example = "20")
		int size,
		@Schema(description = "Total matching organisations.", example = "1")
		long total) {
}
