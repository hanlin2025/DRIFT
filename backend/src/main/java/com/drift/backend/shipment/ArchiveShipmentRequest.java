package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Archives a shipment by setting its stored status. Only ARCHIVED is accepted.")
public record ArchiveShipmentRequest(
		@Schema(description = "Required archive status.", example = "ARCHIVED",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "Status is required")
		@Pattern(regexp = "ARCHIVED", message = "Status must be ARCHIVED")
		String status) {
}
