package com.drift.backend.shipment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "shipment_imports")
public class ShipmentImport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "company_id", nullable = false)
	private Company company;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by_user_id", nullable = false)
	private UserAccount createdBy;

	@Column(name = "imported_count", nullable = false)
	private int importedCount;

	@Column(name = "failed_count", nullable = false)
	private int failedCount;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@OneToMany(mappedBy = "shipmentImport", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("rowNumber ASC")
	private List<ShipmentImportError> errors = new ArrayList<>();

	protected ShipmentImport() {
	}

	public ShipmentImport(Company company, UserAccount createdBy, int importedCount, int failedCount) {
		this.company = company;
		this.createdBy = createdBy;
		this.importedCount = importedCount;
		this.failedCount = failedCount;
		this.createdAt = Instant.now();
	}

	public void addError(int rowNumber, String reason) {
		errors.add(new ShipmentImportError(this, rowNumber, reason));
	}

	public Long getId() { return id; }
	public int getImportedCount() { return importedCount; }
	public int getFailedCount() { return failedCount; }
	public List<ShipmentImportError> getErrors() { return errors; }
}
