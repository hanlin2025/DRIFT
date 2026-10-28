package com.drift.backend.shipment.history;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Shipments", description = "Create shipments and their planned transshipment itineraries.")
public class ShipmentHistoryController {

	private final ShipmentHistoryService history;

	public ShipmentHistoryController(ShipmentHistoryService history) {
		this.history = history;
	}

	@GetMapping("/api/shipments/{shipmentId}/history")
	@Operation(summary = "Get a shipment's change history", description = "Returns the recorded changes of one shipment visible to the authenticated user's active company, "
			+ "including a shipment linked to that company as the importer, newest first. "
			+ "Each entry has the change type, the user, the time, and for an edit only the fields that changed with their previous and new values. "
			+ "The creation is the last entry, so a shipment with only that entry has no changes yet. "
			+ "earlierChangesUnrecorded is true for a shipment that was changed before history was recorded. "
			+ "A shipment of another company is forbidden, and an unknown id is reported as not found.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "History of the shipment", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentHistoryResponse.class))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company, or the shipment belongs to another company", content = @Content(
						mediaType = "application/json", schemaProperties = @SchemaProperty(name = "message", schema = @Schema(type = "string")), examples = {
								@ExampleObject(name = "No active company", value = """
										{"message":"Your account must belong to an active company to view shipments"}
										"""),
								@ExampleObject(name = "Shipment of another company", value = """
										{"message":"You do not have access to this shipment"}
										""") })),
				@ApiResponse(responseCode = "404", description = "No shipment exists with this id", content = @Content(
						mediaType = "application/json", schemaProperties = @SchemaProperty(name = "message", schema = @Schema(type = "string")), examples = @ExampleObject(value = """
								{"message":"Shipment not found"}
								"""))) })
	public ResponseEntity<ShipmentHistoryResponse> history(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.history(user, shipmentId));
	}
}
