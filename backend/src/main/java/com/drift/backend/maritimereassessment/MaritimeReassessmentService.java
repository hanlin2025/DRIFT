package com.drift.backend.maritimereassessment;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentService;

/** Authorizes a viewer, then records or reads reassessments. Reading does not start a reassessment. */
@Service
public class MaritimeReassessmentService {

	private final ShipmentService shipments;
	private final MaritimeContextService context;
	private final MaritimeReassessmentOrchestrator orchestrator;
	private final MaritimeReassessmentStore store;

	public MaritimeReassessmentService(ShipmentService shipments, MaritimeContextService context,
			MaritimeReassessmentOrchestrator orchestrator, MaritimeReassessmentStore store) {
		this.shipments = shipments;
		this.context = context;
		this.orchestrator = orchestrator;
		this.store = store;
	}

	@Transactional
	public MaritimeReassessmentResult review(AuthenticatedUser viewer, long shipmentId, MaritimeEvidenceBundle bundle) {
		ShipmentDetailResponse shipment = visible(viewer, shipmentId);
		return orchestrator.review(shipment.id(), shipment.companyId(), bundle);
	}

	@Transactional
	public MaritimeReassessmentResult reviewCurrentEvidence(AuthenticatedUser viewer, long shipmentId, String datasetId) {
		ShipmentDetailResponse shipment = visible(viewer, shipmentId);
		var current = context.forShipment(shipment);
		Instant cutoff = Instant.parse(current.assessmentCutoffUtc());
		return orchestrator.review(shipment.id(), shipment.companyId(),
				new MaritimeEvidenceBundle(datasetId, cutoff, current.evidence(), List.of()));
	}

	@Transactional(readOnly = true)
	public MaritimeReassessmentHistory history(AuthenticatedUser viewer, long shipmentId) {
		ShipmentDetailResponse shipment = visible(viewer, shipmentId);
		return MaritimeReassessmentHistory.of(shipment.id(), store.history(shipment.id()));
	}

	private ShipmentDetailResponse visible(AuthenticatedUser viewer, long shipmentId) {
		ShipmentDetailResponse shipment = shipments.get(viewer, shipmentId);
		if (shipment.id() == null || shipment.companyId() == null) {
			throw new IllegalStateException("A visible shipment needs its id and owning company");
		}
		return shipment;
	}
}
