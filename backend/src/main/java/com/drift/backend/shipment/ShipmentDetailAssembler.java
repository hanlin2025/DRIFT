package com.drift.backend.shipment;

import org.springframework.stereotype.Component;

@Component
class ShipmentDetailAssembler {

	private final ShipmentConnectionRisk connectionRisk;
	private final ShipmentImporterConstraintRepository constraints;
	private final RecommendationDecisionService decisions;

	ShipmentDetailAssembler(ShipmentConnectionRisk connectionRisk, ShipmentImporterConstraintRepository constraints,
			RecommendationDecisionService decisions) {
		this.connectionRisk = connectionRisk;
		this.constraints = constraints;
		this.decisions = decisions;
	}

	ShipmentDetailResponse detail(Shipment shipment) {
		return ShipmentDetailResponse.from(shipment, connectionRisk.assess(shipment), deadline(shipment),
				decisions.current(shipment.getId()), decisions.history(shipment.getId()), decisions.currentImpact());
	}

	private TransshipmentArrivalDeadlineResponse deadline(Shipment shipment) {
		if (shipment.getImporterCompany() == null || shipment.getImporterCompany().getId() == null) {
			return null;
		}
		return constraints.findByShipmentIdAndImporterCompanyId(shipment.getId(), shipment.getImporterCompany().getId())
				.map(constraint -> TransshipmentArrivalDeadlineResponse.from(shipment, constraint))
				.orElse(null);
	}
}
