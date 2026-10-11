package com.drift.backend.shipment.csvimport;

import java.io.ByteArrayInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


@Service
public class ShipmentImportJobProcessor {

	private static final Logger LOGGER = LoggerFactory.getLogger(ShipmentImportJobProcessor.class);

	private final ShipmentImportJobService jobs;
	private final ShipmentImportCsvParser parser;
	private final ShipmentImportDuplicateValidator duplicates;
	private final ShipmentImportJobRowProcessor rows;

	public ShipmentImportJobProcessor(ShipmentImportJobService jobs, ShipmentImportCsvParser parser,
			ShipmentImportDuplicateValidator duplicates, ShipmentImportJobRowProcessor rows) {
		this.jobs = jobs;
		this.parser = parser;
		this.duplicates = duplicates;
		this.rows = rows;
	}

	public void process(Long jobId) {
		ShipmentImportJobInput input = jobs.start(jobId);
		if (input == null) {
			return;
		}

		try {
			ShipmentImportParseResult parsed = parser.parse(new ByteArrayInputStream(input.csv()));
			ShipmentImportParseResult validated = duplicates.validate(input.managingCompanyId(), parsed);
			int totalRows = validated.validRows().size()
					+ (int) validated.errors().stream().map(ShipmentImportRowError::rowNumber).distinct().count();
			jobs.recordInitialErrors(input.jobId(), totalRows, validated.errors());
			for (ShipmentImportRow row : validated.validRows()) {
				rows.process(input.jobId(), row);
			}
			jobs.complete(input.jobId());
		} catch (ShipmentImportFileException exception) {
			jobs.fail(input.jobId(), exception.getMessage());
		} catch (RuntimeException exception) {
			LOGGER.error("Shipment import job {} failed", input.jobId(), exception);
			jobs.fail(input.jobId(), "Import processing failed");
		}
	}

}
