package com.drift.backend.cdg201;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "CDG-201", description = "Read-only, provenance-preserving external-arrival evidence replays.")
public class Cdg201ArrivalEvidenceController {

	private final ShipmentService shipments;
	private final Cdg201ArrivalEvidenceService evidence;

	public Cdg201ArrivalEvidenceController(ShipmentService shipments, Cdg201ArrivalEvidenceService evidence) {
		this.shipments = shipments;
		this.evidence = evidence;
	}

	@GetMapping("/api/shipments/{shipmentId}/cdg201-arrival-evidence")
	@Operation(summary = "Get read-only CDG-201 arrival evidence replay",
			description = "Returns fixture-backed recorded MPA ETA-revision evidence only when the shipment's stored mother-vessel IMO matches the replay. "
					+ "It never updates shipment dates, AIS arrival estimates, risk, or alerts. A null body means no replay is available.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponse(responseCode = "200", description = "Replay envelope; evidence is null when no replay matches the visible shipment",
			content = @Content(mediaType = "application/json", schema = @Schema(implementation = Cdg201ArrivalEvidenceReplayResponse.class)))
	public ResponseEntity<Cdg201ArrivalEvidenceReplayResponse> get(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId) {
		if (user == null) throw new SessionEndedException();
		ShipmentDetailResponse shipment = shipments.get(user, shipmentId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new Cdg201ArrivalEvidenceReplayResponse(true, evidence.forShipment(shipment)));
	}
}
