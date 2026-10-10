package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Importer organisation to link. A null id removes the current link.")
public record LinkImporterRequest(
		@Schema(description = "Active company other than the shipment's own company. Null removes the link.",
				example = "2", nullable = true)
		Long importerCompanyId) {
}
