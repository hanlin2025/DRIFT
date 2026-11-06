package com.drift.backend.maritimechange;

import java.util.List;

import org.springframework.stereotype.Component;

import com.drift.backend.shipment.Shipment;
import com.drift.backend.shipment.ShipmentRepository;

@Component
class PersistentVisibleShipments implements VisibleShipments {

	private final ShipmentRepository shipments;

	PersistentVisibleShipments(ShipmentRepository shipments) {
		this.shipments = shipments;
	}

	@Override
	public List<ShipmentMatchFacts> forCompany(long companyId) {
		return shipments.findVisibleByCompanyIdOrderByCreatedAtDesc(companyId).stream()
				.map(PersistentVisibleShipments::facts)
				.toList();
	}

	private static ShipmentMatchFacts facts(Shipment shipment) {
		return new ShipmentMatchFacts(shipment.getId(), shipment.getCompany().getId(), shipment.getTransshipmentPort(),
				shipment.getMotherVesselImo(), shipment.getFeederVesselImo(), shipment.getPlannedMotherArrivalAt(),
				shipment.getPlannedFeederDepartureAt());
	}
}
