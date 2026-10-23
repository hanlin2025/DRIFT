package com.drift.backend.shipment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface ShipmentRiskAlertActivationRepository extends JpaRepository<ShipmentRiskAlertActivation, Short> {
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("SELECT activation FROM ShipmentRiskAlertActivation activation WHERE activation.id = 1")
	Optional<ShipmentRiskAlertActivation> lockForRiskInput();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT activation FROM ShipmentRiskAlertActivation activation WHERE activation.id = 1")
	Optional<ShipmentRiskAlertActivation> lockForActivation();
}
