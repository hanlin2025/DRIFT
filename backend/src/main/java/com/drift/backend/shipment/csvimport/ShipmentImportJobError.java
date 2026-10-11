package com.drift.backend.shipment.csvimport;

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
@Table(name = "shipment_import_job_errors")
public class ShipmentImportJobError {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shipment_import_job_id", nullable = false)
	private ShipmentImportJob job;

	@Column(name = "row_number", nullable = false)
	private long rowNumber;

	@Column(name = "column_name", length = 255)
	private String columnName;

	@Column(name = "error_code", nullable = false, length = 100)
	private String errorCode;

	@Column(nullable = false)
	private String message;

	protected ShipmentImportJobError() {
	}

	public ShipmentImportJobError(ShipmentImportJob job, ShipmentImportRowError error) {
		this.job = job;
		this.rowNumber = error.rowNumber();
		this.columnName = error.column();
		this.errorCode = error.code();
		this.message = error.message();
	}

	public Long getId() { return id; }
	public long getRowNumber() { return rowNumber; }
	public String getColumnName() { return columnName; }
	public String getErrorCode() { return errorCode; }
	public String getMessage() { return message; }
}
