package com.drift.backend.shipment.csvimport;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "shipment_import_jobs")
public class ShipmentImportJob {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "managing_company_id", nullable = false)
	private Company managingCompany;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requested_by_user_id", nullable = false)
	private UserAccount requestedBy;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ShipmentImportJobStatus status;

	@Column(name = "original_filename", nullable = false, length = 255)
	private String originalFilename;

	@Column(name = "file_size_bytes", nullable = false)
	private long fileSizeBytes;

	@Column(name = "content_type", length = 255)
	private String contentType;

	@JdbcTypeCode(SqlTypes.LONGVARBINARY)
	@Column(name = "source_csv")
	private byte[] sourceCsv;

	@Column(name = "total_rows", nullable = false)
	private int totalRows;

	@Column(name = "processed_rows", nullable = false)
	private int processedRows;

	@Column(name = "imported_count", nullable = false)
	private int importedCount;

	@Column(name = "failed_count", nullable = false)
	private int failedCount;

	@Column(name = "failure_message")
	private String failureMessage;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected ShipmentImportJob() {
	}

	public ShipmentImportJob(Company managingCompany, UserAccount requestedBy, String originalFilename,
			long fileSizeBytes, String contentType, byte[] sourceCsv) {
		this.managingCompany = managingCompany;
		this.requestedBy = requestedBy;
		this.status = ShipmentImportJobStatus.PENDING;
		this.originalFilename = originalFilename;
		this.fileSizeBytes = fileSizeBytes;
		this.contentType = contentType;
		this.sourceCsv = sourceCsv;
		this.createdAt = Instant.now();
	}

	public boolean start() {
		if (status != ShipmentImportJobStatus.PENDING) {
			return false;
		}
		status = ShipmentImportJobStatus.PROCESSING;
		startedAt = Instant.now();
		return true;
	}

	public void initializeRows(int totalRows, int failedRows) {
		this.totalRows = totalRows;
		this.processedRows = failedRows;
		this.failedCount = failedRows;
	}

	public void recordImportedRow() {
		processedRows++;
		importedCount++;
	}

	public void recordFailedRow() {
		processedRows++;
		failedCount++;
	}

	public void complete() {
		status = ShipmentImportJobStatus.COMPLETED;
		completedAt = Instant.now();
		clearSourceCsv();
	}

	public void fail(String message) {
		status = ShipmentImportJobStatus.FAILED;
		failureMessage = message;
		completedAt = Instant.now();
		clearSourceCsv();
	}

	private void clearSourceCsv() {
		sourceCsv = null;
	}

	public Long getId() { return id; }
	public Company getManagingCompany() { return managingCompany; }
	public UserAccount getRequestedBy() { return requestedBy; }
	public ShipmentImportJobStatus getStatus() { return status; }
	public String getOriginalFilename() { return originalFilename; }
	public long getFileSizeBytes() { return fileSizeBytes; }
	public String getContentType() { return contentType; }
	public byte[] getSourceCsv() { return sourceCsv; }
	public int getTotalRows() { return totalRows; }
	public int getProcessedRows() { return processedRows; }
	public int getImportedCount() { return importedCount; }
	public int getFailedCount() { return failedCount; }
	public String getFailureMessage() { return failureMessage; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getStartedAt() { return startedAt; }
	public Instant getCompletedAt() { return completedAt; }
}
