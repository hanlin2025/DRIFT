package com.drift.backend.shipment;

import java.util.List;

/** One fixed-size page of shipments the caller is authorised to view. */
public record ShipmentPage(List<ShipmentResponse> items, int page, int size, long total) {

	public ShipmentPage {
		items = List.copyOf(items);
	}
}
