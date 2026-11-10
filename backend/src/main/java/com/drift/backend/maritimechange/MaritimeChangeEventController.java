package com.drift.backend.maritimechange;

import java.time.Instant;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.ShipmentService;
import com.drift.backend.shipment.ShipmentViewAuthorization;

/** Authenticated, shipment-authorized retrieval of persisted source-change evidence. */
@RestController
public class MaritimeChangeEventController {
	private final ShipmentService shipments;
	private final MaritimeChangeEventService events;

	public MaritimeChangeEventController(ShipmentService shipments, MaritimeChangeEventService events) {
		this.shipments = shipments;
		this.events = events;
	}

	@GetMapping("/api/shipments/{shipmentId}/maritime-change-events")
	public ResponseEntity<MaritimeChangeEventResponse> get(
			@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId,
			@RequestParam(required = false) Instant since,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String cursor) {
		if (user == null) throw new SessionEndedException();
		ShipmentViewAuthorization access = shipments.authorizeView(user, shipmentId);
		MaritimeChangeEventResponse response = events.get(access.viewerCompanyId(), shipmentId, since, limit, cursor);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}
}
