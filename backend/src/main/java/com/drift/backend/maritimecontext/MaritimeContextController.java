package com.drift.backend.maritimecontext;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.ShipmentService;

/** CDG-245: a non-mutating read backed only by an explicitly enabled development fixture. */
@RestController
public class MaritimeContextController {
	private final ShipmentService shipments;
	private final MaritimeContextService context;

	public MaritimeContextController(ShipmentService shipments, MaritimeContextService context) {
		this.shipments = shipments;
		this.context = context;
	}

	@GetMapping("/api/shipments/{shipmentId}/maritime-context")
	public ResponseEntity<MaritimeContextResponse> get(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long shipmentId) {
		if (user == null) throw new SessionEndedException();
		// ShipmentService owns the existing active-company, visibility, 403, and 404 semantics.
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(context.forShipment(shipments.get(user, shipmentId)));
	}
}
