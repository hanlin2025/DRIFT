package com.drift.backend.shipment;

/** Published in the shipment transaction and handled only after that transaction commits. */
public record ShipmentRiskEvaluationRequestedEvent(Long shipmentId) {
}
