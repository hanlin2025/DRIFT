package com.drift.backend.shipment.history;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentChangeEventRepository extends JpaRepository<ShipmentChangeEvent, Long> {
}
