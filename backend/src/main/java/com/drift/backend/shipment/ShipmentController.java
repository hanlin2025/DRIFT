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

import jakarta.validation.Valid;

@RestController
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
	public ResponseEntity<ShipmentResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateShipmentRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(shipmentService.create(user, request));
	}
}
