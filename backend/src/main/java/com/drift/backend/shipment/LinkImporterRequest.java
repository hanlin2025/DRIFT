package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Importer organisation to link. Null removes the link.")
public record LinkImporterRequest(
		@Schema(description = "Active importer organisation other than the shipment's own company. Null removes the link.",
				example = "2", nullable = true)
		Long importerCompanyId) {
}
