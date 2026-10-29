package com.drift.backend.maritimecontext;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.drift.backend.shipment.ShipmentDetailResponse;

@Service
public class MaritimeContextService {
	private final boolean demoEnabled;
	private final List<MaritimeEvidenceSourceAdapter> adapters;
	private final Clock clock;

	public MaritimeContextService(@Value("${app.maritime-context.demo-enabled:false}") boolean demoEnabled,
			List<MaritimeEvidenceSourceAdapter> adapters) {
		this(demoEnabled, adapters, Clock.systemUTC());
	}

	MaritimeContextService(boolean demoEnabled, List<MaritimeEvidenceSourceAdapter> adapters, Clock clock) {
		this.demoEnabled = demoEnabled;
		this.adapters = List.copyOf(adapters);
		this.clock = clock;
	}

	public MaritimeContextResponse forShipment(ShipmentDetailResponse shipment) {
		return forShipment(shipment, clock.instant());
	}

	MaritimeContextResponse forShipment(ShipmentDetailResponse shipment, Instant assessmentCutoffUtc) {
		if (!demoEnabled) {
			return new MaritimeContextResponse("cdg246.maritime-evidence-bundle.v1", false, "DISABLED", assessmentCutoffUtc.toString(), "UNAVAILABLE",
					"Maritime context is disabled. No live maritime intelligence has been queried.", List.of(), List.of(), null, null);
		}
		List<MaritimeEvidence> evidence = adapters.stream()
				.flatMap(adapter -> adapter.forShipment(shipment, assessmentCutoffUtc).stream()).toList();
		List<MaritimeNoticeEvidence> notices = evidence.stream().filter(item -> "MPA_OFFICIAL_NOTICE".equals(item.sourceType()))
				.map(item -> new MaritimeNoticeEvidence(
						item.evidenceId(), item.title(), item.sourceUrl(), item.citation(), item.sourceEventAt(), item.observedAt(),
						null, null, "UNKNOWN", item.compatibilityState(), item.evidenceSufficient(), item.humanReviewRequired(),
						item.shipmentAssociationConfidence(), List.of(item.activityCompatibility()), List.of(item.locationCompatibility()), item.limitation()))
				.toList();
		String association = evidence.stream().anyMatch(item -> "UNVERIFIED_IMO_ONLY".equals(item.shipmentAssociationConfidence()))
				? "UNVERIFIED_IMO_ONLY" : "UNAVAILABLE";
		return new MaritimeContextResponse("cdg246.maritime-evidence-bundle.v1", true, bundleMode(evidence), assessmentCutoffUtc.toString(), association,
				"Human review is required. This bundle is a read-only replay/demo and does not change ETA, shipment, risk, alerts, or operational decisions.",
				evidence, notices, null, null);
	}

	private static String bundleMode(List<MaritimeEvidence> evidence) {
		if (evidence.stream().anyMatch(item -> "RETROSPECTIVE_ANALYSIS".equals(item.sourceMode()))) return "RETROSPECTIVE_ANALYSIS";
		if (evidence.stream().anyMatch(item -> "RECORDED_REPLAY".equals(item.sourceMode()))) return "RECORDED_REPLAY";
		if (evidence.stream().anyMatch(item -> "SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))) return "SYNTHETIC_DEMONSTRATION";
		return "UNAVAILABLE";
	}
}
