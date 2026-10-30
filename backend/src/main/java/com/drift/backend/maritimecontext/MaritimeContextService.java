package com.drift.backend.maritimecontext;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.drift.backend.shipment.ShipmentDetailResponse;

@Service
public class MaritimeContextService {
	private final boolean demoEnabled;

	public MaritimeContextService(@Value("${app.maritime-context.demo-enabled:false}") boolean demoEnabled) {
		this.demoEnabled = demoEnabled;
	}

	public MaritimeContextResponse forShipment(ShipmentDetailResponse shipment) {
		if (!demoEnabled) {
			return new MaritimeContextResponse(false, "DISABLED", "UNAVAILABLE",
					"Maritime context is disabled. No live maritime intelligence has been queried.", List.of(), null, null);
		}
		if (!"9351048".equals(shipment.motherVesselImo())) {
			return new MaritimeContextResponse(true, "SYNTHETIC_DEMONSTRATION", "UNAVAILABLE",
					"The demonstration fixture applies only to the recorded KOTA LARIS replay vessel; no context is associated with this shipment.", List.of(), null, null);
		}
		MaritimeNoticeEvidence notice = new MaritimeNoticeEvidence(
				"DEMO-MPA-245-001", "Demonstration maritime operational notice",
				"https://www.mpa.gov.sg/", "Synthetic demonstration citation; not a live official notice.",
				"2026-10-27T00:00:00+08:00", "2026-10-27T00:05:00Z",
				"2026-10-27T00:00:00+08:00", "2026-10-28T00:00:00+08:00", "NOT_SUPERSEDED",
				"UNKNOWN", false, true, "UNVERIFIED_IMO_ONLY",
				List.of("Synthetic activity evidence for UI demonstration only."),
				List.of("No terminal or berth attribution is made."),
				"Synthetic demonstration data. It is not a verified disruption, shipment association, or operational recommendation.");
		return new MaritimeContextResponse(true, "SYNTHETIC_DEMONSTRATION", "UNVERIFIED_IMO_ONLY",
				"Human review is required. Compatibility is unknown and no risk, ETA, or shipment record is changed.", List.of(notice), null, null);
	}
}
