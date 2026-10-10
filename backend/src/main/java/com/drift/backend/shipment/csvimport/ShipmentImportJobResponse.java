package com.drift.backend.shipment.csvimport;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Status and progress for an asynchronous shipment CSV import.")
public record ShipmentImportJobResponse(
		Long id,
		ShipmentImportJobStatus status,
		String originalFilename,
		long fileSizeBytes,
		int totalRows,
		int processedRows,
		int importedCount,
		int failedCount,
		String failureMessage,
		Instant createdAt,
		Instant startedAt,
		Instant completedAt) {

	static ShipmentImportJobResponse from(ShipmentImportJob job) {
		return new ShipmentImportJobResponse(job.getId(), job.getStatus(), job.getOriginalFilename(),
				job.getFileSizeBytes(), job.getTotalRows(), job.getProcessedRows(), job.getImportedCount(),
				job.getFailedCount(), job.getFailureMessage(), job.getCreatedAt(), job.getStartedAt(), job.getCompletedAt());
	}
}
