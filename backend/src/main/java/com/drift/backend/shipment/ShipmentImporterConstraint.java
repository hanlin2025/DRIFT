package com.drift.backend.shipment;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "shipment_importer_constraints")
@IdClass(ShipmentImporterConstraintId.class)
public class ShipmentImporterConstraint {

	@Id
	@Column(name = "shipment_id")
	private Long shipmentId;

	@Id
	@Column(name = "importer_company_id")
	private Long importerCompanyId;

	@Column(name = "transshipment_port", nullable = false, length = 200)
	private String transshipmentPort;

	@Column(name = "transshipment_arrival_deadline", nullable = false)
	private OffsetDateTime deadline;

	protected ShipmentImporterConstraint() {
	}

	public ShipmentImporterConstraint(Long shipmentId, Long importerCompanyId, String transshipmentPort,
			OffsetDateTime deadline) {
		this.shipmentId = shipmentId;
		this.importerCompanyId = importerCompanyId;
		this.transshipmentPort = transshipmentPort;
		this.deadline = deadline;
	}

	public Long getShipmentId() {
		return shipmentId;
	}

	public Long getImporterCompanyId() {
		return importerCompanyId;
	}

	public String getTransshipmentPort() {
		return transshipmentPort;
	}

	public OffsetDateTime getDeadline() {
		return deadline;
	}

	public void confirm(String transshipmentPort, OffsetDateTime deadline) {
		this.transshipmentPort = transshipmentPort;
		this.deadline = deadline;
	}
}
