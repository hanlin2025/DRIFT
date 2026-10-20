package com.drift.backend.shipment;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** One fixed-size page of shipments the caller is authorised to view. */
@Schema(description = "Returned when page, sort, or direction is present. items contains at most size shipments.")
public record ShipmentPage(
		@Schema(description = "Shipments on this page. Empty when the requested page is past the end.")
		List<ShipmentResponse> items,
		@Schema(description = "One-based page number. Defaults to 1 when paging is requested and page is omitted.", example = "1")
		int page,
		@Schema(description = "Fixed page size.", example = "25")
		int size,
		@Schema(description = "Total shipments the caller is authorised to view that match the filters.", example = "1")
		long total) {

	public ShipmentPage {
		items = List.copyOf(items);
	}
}
