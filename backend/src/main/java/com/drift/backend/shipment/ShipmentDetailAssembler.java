package com.drift.backend.shipment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
		ShipmentConnectionRisk.Snapshot connection = connectionRisk.assess(shipment);
		TransshipmentArrivalDeadlineResponse recorded = deadline(shipment);
		DelayComparison comparison = DelayComparison.of(connection.arrivalComparison(), recorded);
		return ShipmentDetailResponse.from(shipment, connection, recorded,
				decisions.current(shipment.getId()), decisions.history(shipment.getId()),
				decisions.impactFor(comparison), comparison);
	}

	ShipmentResponse row(Shipment shipment) {
		return ShipmentResponse.from(shipment, connectionRisk.assess(shipment), deadline(shipment));
	}

	/**
	 * Builds list rows with one batched AIS lookup and one constraint query, so each row's risk and delay comparison
	 * match its detail page without a query per row.
	 */
	List<ShipmentResponse> rows(List<Shipment> listed) {
		List<ShipmentConnectionRisk.Snapshot> connections = connectionRisk.assessAll(listed);
		Map<Long, List<ShipmentImporterConstraint>> recorded = listed.isEmpty() ? Map.of()
				: constraints.findByShipmentIdIn(listed.stream().map(Shipment::getId).toList()).stream()
						.collect(Collectors.groupingBy(ShipmentImporterConstraint::getShipmentId));
		List<ShipmentResponse> responses = new ArrayList<>(listed.size());
		for (int i = 0; i < listed.size(); i++) {
			Shipment shipment = listed.get(i);
			Long importerId = importerId(shipment);
			TransshipmentArrivalDeadlineResponse deadline = importerId == null ? null
					: recorded.getOrDefault(shipment.getId(), List.of()).stream()
							.filter(constraint -> importerId.equals(constraint.getImporterCompanyId()))
							.findFirst()
							.map(constraint -> TransshipmentArrivalDeadlineResponse.from(shipment, constraint))
							.orElse(null);
			responses.add(ShipmentResponse.from(shipment, connections.get(i), deadline));
		}
		return responses;
	}

	private TransshipmentArrivalDeadlineResponse deadline(Shipment shipment) {
		Long importerId = importerId(shipment);
		if (importerId == null) {
			return null;
		}
		return constraints.findByShipmentIdAndImporterCompanyId(shipment.getId(), importerId)
				.map(constraint -> TransshipmentArrivalDeadlineResponse.from(shipment, constraint))
				.orElse(null);
	}

	private static Long importerId(Shipment shipment) {
		return shipment.getImporterCompany() == null ? null : shipment.getImporterCompany().getId();
	}
}
