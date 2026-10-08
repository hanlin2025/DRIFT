package com.drift.backend.shipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "shipment_import_errors")
public class ShipmentImportError {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "import_id", nullable = false)
	private ShipmentImport shipmentImport;

	@Column(name = "row_number", nullable = false)
	private int rowNumber;

	@Column(nullable = false, length = 500)
	private String reason;

	protected ShipmentImportError() {
	}

	ShipmentImportError(ShipmentImport shipmentImport, int rowNumber, String reason) {
		this.shipmentImport = shipmentImport;
		this.rowNumber = rowNumber;
		this.reason = reason;
	}

	public int getRowNumber() { return rowNumber; }
	public String getReason() { return reason; }
}
