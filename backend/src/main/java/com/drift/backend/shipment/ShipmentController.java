package com.drift.backend.shipment;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@Tag(name = "Shipments", description = "Create shipments and their planned transshipment itineraries.")
public class ShipmentController {

	private final ShipmentService shipmentService;

	public ShipmentController(ShipmentService shipmentService) {
		this.shipmentService = shipmentService;
	}

	@GetMapping("/api/shipments")
	public ResponseEntity<List<ShipmentResponse>> list(@AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.list(user));
	}

	@PostMapping("/api/shipments")
	@Operation(summary = "Create a shipment", description = "Creates a shipment for the authenticated user's active company. "
			+ "Shipment references are unique within that company, ignoring letter case.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "201", description = "Shipment created", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentResponse.class))),
				@ApiResponse(responseCode = "400", description = "Missing or invalid shipment data, including an invalid itinerary", content = @Content(
						mediaType = "application/json", examples = {
								@ExampleObject(name = "Missing field", value = """
										{"message":"Shipment information is missing or invalid","errors":{"origin":"Origin is required"}}
										"""),
								@ExampleObject(name = "Invalid itinerary", value = """
										{"message":"Planned feeder-vessel departure must be after planned mother-vessel arrival","errors":{"plannedFeederDepartureAt":"Planned feeder-vessel departure must be after planned mother-vessel arrival"}}
										""") })),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Please sign in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your account must belong to an active company to create shipments"}
								"""))),
				@ApiResponse(responseCode = "409", description = "Shipment reference already exists for the authenticated user's company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"A shipment with this reference already exists for your company"}
								"""))) })
	public ResponseEntity<ShipmentResponse> create(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateShipmentRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(shipmentService.create(user, request));
	}
}
