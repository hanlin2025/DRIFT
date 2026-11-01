package com.drift.backend.shipment;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;

@RestController
@Tag(name = "Shipments", description = "Create shipments and their planned transshipment itineraries.")
public class ShipmentConstraintController {

	private final ShipmentConstraintService constraints;

	public ShipmentConstraintController(ShipmentConstraintService constraints) {
		this.constraints = constraints;
	}

	@PatchMapping("/api/shipments/{shipmentId}/transshipment-arrival-deadline")
	@Operation(summary = "Record or clear the linked importer's transshipment-arrival deadline",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	public ResponseEntity<ShipmentDetailResponse> update(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId,
			@RequestBody(required = false) JsonNode body) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(constraints.update(user, shipmentId, body));
	}
}
