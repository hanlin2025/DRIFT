package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Sets a shipment's stored status. ARCHIVED archives an active shipment. ACTIVE restores an archived shipment.")
public record ArchiveShipmentRequest(
		@Schema(description = "Required status.", example = "ARCHIVED",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "Status is required")
		@Pattern(regexp = "ACTIVE|ARCHIVED", message = "Status must be ACTIVE or ARCHIVED")
		String status) {
}
