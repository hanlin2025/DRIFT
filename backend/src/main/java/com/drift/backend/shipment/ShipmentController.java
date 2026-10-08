package com.drift.backend.shipment;

import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
	@Operation(summary = "List shipments", description = "Lists the active shipments of the authenticated user's active company, newest first. "
			+ "Archived shipments are excluded. "
			+ "Each shipment includes the planned connection window calculated from its stored schedule.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Active shipments of the company, possibly empty", content = @Content(
						mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ShipmentResponse.class)))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your account must belong to an active company to view shipments"}
								"""))) })
	public ResponseEntity<List<ShipmentResponse>> list(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.list(user));
	}

	@GetMapping("/api/shipments/{shipmentId}")
	@Operation(summary = "Get a shipment", description = "Returns one shipment of the authenticated user's active company, "
			+ "including the planned connection window calculated from the stored mother-vessel arrival and feeder-vessel departure, "
			+ "and the latest retained AIS position for each vessel name. "
			+ "A shipment of another company is reported as not found.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Shipment found", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentDetailResponse.class))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your account must belong to an active company to view shipments"}
								"""))),
				@ApiResponse(responseCode = "404", description = "No shipment with this id belongs to the authenticated user's company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment not found"}
								"""))) })
	public ResponseEntity<ShipmentDetailResponse> get(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.get(user, shipmentId));
	}

	@GetMapping("/api/shipments/{shipmentId}/tracking")
	@Operation(summary = "Get shipment vessel tracking", description = "Returns the latest retained AIS position for the mother vessel and the feeder vessel "
			+ "of one shipment belonging to the authenticated user's active company. "
			+ "Each position includes coordinates, speed, course, heading, and the time it was ingested. "
			+ "A missing position is null. This is a live fix, not an arrival estimate. "
			+ "A shipment of another company is reported as not found.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Tracking for the shipment, with a null position where no AIS report is retained", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentTrackingResponse.class))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your account must belong to an active company to view shipments"}
								"""))),
				@ApiResponse(responseCode = "404", description = "No shipment with this id belongs to the authenticated user's company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment not found"}
								"""))) })
	public ResponseEntity<ShipmentTrackingResponse> tracking(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.tracking(user, shipmentId));
	}

	@PostMapping("/api/shipments")
	@Operation(summary = "Create a shipment", description = "Creates a shipment for the authenticated user's active company. "
			+ "Shipment references are unique within that company, ignoring letter case. "
			+ "The response includes the planned connection window, calculated from the stored mother-vessel arrival and feeder-vessel departure.",
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
								{"message":"Your session has ended. Log in again."}
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

	@PatchMapping("/api/shipments/{shipmentId}/status")
	@Operation(summary = "Archive a shipment", description = "Sets an active shipment of the authenticated user's company to ARCHIVED and keeps the stored record. "
			+ "Only an Admin or Logistics Manager of that company may archive it. "
			+ "The shipment no longer appears in the active list. "
			+ "A shipment of another company is reported as not found. "
			+ "An account with no active company is forbidden.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Shipment archived", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment removed successfully"}
								"""))),
				@ApiResponse(responseCode = "400", description = "Shipment id is not a number, or the status is missing or not ARCHIVED", content = @Content(
						mediaType = "application/json", examples = {
								@ExampleObject(name = "Invalid id", value = """
										{"message":"Invalid Shipment ID."}
										"""),
								@ExampleObject(name = "Invalid status", value = """
										{"message":"Shipment information is missing or invalid","errors":{"status":"Status must be ARCHIVED"}}
										""") })),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Account has no active company, or the role cannot archive shipments", content = @Content(
						mediaType = "application/json", examples = {
								@ExampleObject(name = "No active company", value = """
										{"message":"Your account must belong to an active company to view shipments"}
										"""),
								@ExampleObject(name = "Role", value = """
										{"message":"You do not have permission to archive this shipment."}
										""") })),
				@ApiResponse(responseCode = "404", description = "No shipment with this id belongs to the authenticated user's company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment not found"}
								"""))),
				@ApiResponse(responseCode = "409", description = "Shipment is already archived", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment is already removed or does not exist."}
								"""))) })
	public ResponseEntity<Map<String, String>> archive(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId,
			@Valid @RequestBody ArchiveShipmentRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		shipmentService.archive(user, shipmentId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(Map.of("message", ShipmentService.REMOVED_MESSAGE));
	}
}
