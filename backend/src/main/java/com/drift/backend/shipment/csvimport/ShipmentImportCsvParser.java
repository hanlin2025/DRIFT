package com.drift.backend.shipment.csvimport;

import static com.drift.backend.shipment.csvimport.ShipmentImportCsvContract.DESTINATION_PORT;
import static com.drift.backend.shipment.csvimport.ShipmentImportCsvContract.FEEDER_VESSEL;
import static com.drift.backend.shipment.csvimport.ShipmentImportCsvContract.IMPORTER_ORGANISATION;
import static com.drift.backend.shipment.csvimport.ShipmentImportCsvContract.MOTHER_VESSEL;
import static com.drift.backend.shipment.csvimport.ShipmentImportCsvContract.ORIGIN_PORT;
import static com.drift.backend.shipment.csvimport.ShipmentImportCsvContract.PLANNED_FEEDER_DEPARTURE;
import static com.drift.backend.shipment.csvimport.ShipmentImportCsvContract.PLANNED_MOTHER_ARRIVAL;
import static com.drift.backend.shipment.csvimport.ShipmentImportCsvContract.TRACKING_BL_NUMBER;
import static com.drift.backend.shipment.csvimport.ShipmentImportCsvContract.TRANSSHIPMENT_PORT;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class ShipmentImportCsvParser {

	private static final Pattern OFFSET_DATE_TIME = Pattern.compile(".*(?:Z|[+-]\\d{2}:\\d{2})$");

	public ShipmentImportParseResult parse(InputStream input) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(withoutUtf8ByteOrderMark(input), strictUtf8Decoder()));
				CSVParser parser = CSVFormat.RFC4180.builder()
						.setHeader()
						.setSkipHeaderRecord(true)
						.setIgnoreEmptyLines(false)
						.get()
						.parse(reader)) {
			validateHeaders(parser.getHeaderNames());

			List<ShipmentImportRow> validRows = new ArrayList<>();
			List<ShipmentImportRowError> errors = new ArrayList<>();
			for (CSVRecord record : parser) {
				parseRecord(record, validRows, errors);
			}
			return new ShipmentImportParseResult(validRows, errors);
		} catch (CharacterCodingException exception) {
			throw new ShipmentImportFileException("CSV must be valid UTF-8", exception);
		} catch (UncheckedIOException exception) {
			IOException cause = exception.getCause();
			if (cause instanceof CharacterCodingException) {
				throw new ShipmentImportFileException("CSV must be valid UTF-8", cause);
			}
			throw new ShipmentImportFileException("CSV could not be read", cause);
		} catch (IOException | IllegalArgumentException exception) {
			throw new ShipmentImportFileException("CSV could not be read", exception);
		}
	}

	private void validateHeaders(List<String> headers) {
		if (!ShipmentImportCsvContract.HEADERS.equals(headers)) {
			throw new ShipmentImportFileException("CSV headers must exactly match the shipment import template");
		}
	}

	private void parseRecord(
			CSVRecord record, List<ShipmentImportRow> validRows, List<ShipmentImportRowError> errors) {
		long rowNumber = record.getRecordNumber() + 1;
		List<ShipmentImportRowError> rowErrors = new ArrayList<>();

		if (record.size() > ShipmentImportCsvContract.HEADERS.size()) {
			rowErrors.add(new ShipmentImportRowError(
					rowNumber,
					null,
					"TOO_MANY_COLUMNS",
					"CSV row contains more columns than the shipment import template"));
		}

		String shipmentReference = requiredText(record, TRACKING_BL_NUMBER, 100, rowNumber, rowErrors);
		String origin = requiredText(record, ORIGIN_PORT, 200, rowNumber, rowErrors);
		String destination = requiredText(record, DESTINATION_PORT, 200, rowNumber, rowErrors);
		String transshipmentPort = requiredText(record, TRANSSHIPMENT_PORT, 200, rowNumber, rowErrors);
		String motherVessel = requiredText(record, MOTHER_VESSEL, 200, rowNumber, rowErrors);
		OffsetDateTime plannedMotherArrival = requiredDateTime(record, PLANNED_MOTHER_ARRIVAL, rowNumber, rowErrors);
		String feederVessel = requiredText(record, FEEDER_VESSEL, 200, rowNumber, rowErrors);
		OffsetDateTime plannedFeederDeparture = requiredDateTime(record, PLANNED_FEEDER_DEPARTURE, rowNumber, rowErrors);
		String importerOrganisation = optionalText(value(record, IMPORTER_ORGANISATION));

		if (plannedMotherArrival != null && plannedFeederDeparture != null
				&& !plannedFeederDeparture.isAfter(plannedMotherArrival)) {
			rowErrors.add(new ShipmentImportRowError(
					rowNumber,
					PLANNED_FEEDER_DEPARTURE,
					"INVALID_ITINERARY_TIMING",
					"Planned Feeder Departure must be after Planned Mother Arrival"));
		}

		if (!rowErrors.isEmpty()) {
			errors.addAll(rowErrors);
			return;
		}

		validRows.add(new ShipmentImportRow(
				rowNumber,
				shipmentReference,
				origin,
				destination,
				transshipmentPort,
				motherVessel,
				plannedMotherArrival,
				feederVessel,
				plannedFeederDeparture,
				importerOrganisation));
	}

	private String requiredText(
			CSVRecord record, String column, int maximumLength, long rowNumber, List<ShipmentImportRowError> errors) {
		String value = optionalText(value(record, column));
		if (value == null) {
			errors.add(new ShipmentImportRowError(rowNumber, column, "REQUIRED", column + " is required"));
			return null;
		}
		if (value.length() > maximumLength) {
			errors.add(new ShipmentImportRowError(
					rowNumber,
					column,
					"TOO_LONG",
					column + " must not exceed " + maximumLength + " characters"));
			return null;
		}
		return value;
	}

	private OffsetDateTime requiredDateTime(
			CSVRecord record, String column, long rowNumber, List<ShipmentImportRowError> errors) {
		String value = optionalText(value(record, column));
		if (value == null) {
			errors.add(new ShipmentImportRowError(rowNumber, column, "REQUIRED", column + " is required"));
			return null;
		}
		if (!OFFSET_DATE_TIME.matcher(value).matches()) {
			errors.add(new ShipmentImportRowError(
					rowNumber, column, "INVALID_TIMESTAMP", column + " must include a timezone offset"));
			return null;
		}
		try {
			return OffsetDateTime.parse(value);
		} catch (DateTimeParseException exception) {
			errors.add(new ShipmentImportRowError(
					rowNumber, column, "INVALID_TIMESTAMP", column + " must be an ISO-8601 offset date-time"));
			return null;
		}
	}

	private String optionalText(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private String value(CSVRecord record, String column) {
		return record.isSet(column) ? record.get(column) : "";
	}

	private java.nio.charset.CharsetDecoder strictUtf8Decoder() {
		return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
	}

	private InputStream withoutUtf8ByteOrderMark(InputStream input) throws IOException {
		PushbackInputStream stream = new PushbackInputStream(input, 3);
		byte[] prefix = stream.readNBytes(3);
		if (prefix.length != 3 || prefix[0] != (byte) 0xEF || prefix[1] != (byte) 0xBB || prefix[2] != (byte) 0xBF) {
			stream.unread(prefix);
		}
		return stream;
	}
}
