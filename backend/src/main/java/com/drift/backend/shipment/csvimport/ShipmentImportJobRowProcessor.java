package com.drift.backend.shipment.csvimport;

import org.springframework.stereotype.Service;

import com.drift.backend.shipment.ShipmentService;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;

@Service
public class ShipmentImportJobRowProcessor {

	private final ShipmentImportJobService jobs;
	private final ImporterOrganisationAuthorizer importerAuthorizer;
	private final ShipmentImportShipmentCreator shipments;

	public ShipmentImportJobRowProcessor(ShipmentImportJobService jobs,
			ImporterOrganisationAuthorizer importerAuthorizer, ShipmentImportShipmentCreator shipments) {
		this.jobs = jobs;
		this.importerAuthorizer = importerAuthorizer;
		this.shipments = shipments;
	}

	public void process(Long jobId, ShipmentImportRow row) {
		ShipmentImportJobContext context = jobs.rowContext(jobId);
		ImporterOrganisationResolution importer = importerAuthorizer
				.resolveAuthorizedImporter(context.managingCompanyId(), row);
		if (!importer.isAuthorized()) {
			jobs.recordFailedRow(jobId, importer.error());
			return;
		}

		try {
			shipments.create(context.managingCompanyId(), context.requestedByUserId(), row,
					importer.importerCompany() == null ? null : importer.importerCompany().getId());
			jobs.recordImportedRow(jobId);
		} catch (DuplicateShipmentReferenceException exception) {
			jobs.recordFailedRow(jobId, new ShipmentImportRowError(row.rowNumber(), ShipmentImportCsvContract.TRACKING_BL_NUMBER,
					"DUPLICATE_REFERENCE_IN_DATABASE",
					"Tracking/BL No. already exists for the managing organisation"));
		}
	}
}
