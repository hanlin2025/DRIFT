package com.drift.backend.shipment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.drift.backend.shipment.exception.InvalidShipmentFileException;

public final class ShipmentCsv {

	public static final String DUPLICATE_REFERENCE = "Tracking/BL number already exists.";
	public static final int MAX_ROWS = 500;
	public static final int MAX_BYTES = 1_048_576;

	public static final List<String> TEMPLATE_HEADERS = List.of(
			"Tracking/BL No.",
			"Origin Port",
			"Destination Port",
			"Transshipment Port",
			"Mother Vessel",
			"Planned Mother Arrival",
			"Feeder Vessel",
			"Planned Feeder Departure",
			"Importer Organisation");

	private static final List<String> REQUIRED_HEADERS = TEMPLATE_HEADERS.subList(0, 8);

	private ShipmentCsv() {
	}

	public record Row(int number, Map<String, String> values) {
		public String get(String header) {
			return values.getOrDefault(header, "").strip();
		}
	}

	public static String template() {
		return String.join(",", TEMPLATE_HEADERS) + "\n";
	}

	public static String errorReport(List<RowFailure> failures) {
		StringBuilder report = new StringBuilder("Row,Reason\n");
		for (RowFailure failure : failures) {
			report.append(failure.rowNumber()).append(',').append(quote(failure.reason())).append('\n');
		}
		return report.toString();
	}

	public static List<Row> parse(String text) {
		if (text == null || text.isBlank()) {
			throw new InvalidShipmentFileException();
		}
		String body = text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
		List<List<String>> table = readTable(body);
		if (table.isEmpty()) {
			throw new InvalidShipmentFileException();
		}
		List<String> headers = table.get(0).stream().map(String::strip).toList();
		if (headers.stream().anyMatch(String::isBlank) || headers.stream().distinct().count() != headers.size()
				|| !headers.containsAll(REQUIRED_HEADERS)) {
			throw new InvalidShipmentFileException();
		}
		List<Row> rows = new ArrayList<>();
		for (int index = 1; index < table.size(); index++) {
			List<String> cells = table.get(index);
			if (cells.stream().allMatch(cell -> cell == null || cell.isBlank())) {
				continue;
			}
			if (cells.size() > headers.size()) {
				throw new InvalidShipmentFileException();
			}
			Map<String, String> values = new LinkedHashMap<>();
			for (int column = 0; column < headers.size(); column++) {
				values.put(headers.get(column), column < cells.size() ? cells.get(column) : "");
			}
			rows.add(new Row(index + 1, values));
		}
		if (rows.size() > MAX_ROWS) {
			throw new InvalidShipmentFileException();
		}
		return rows;
	}

	private static List<List<String>> readTable(String text) {
		List<List<String>> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int index = 0; index < text.length(); index++) {
			char current = text.charAt(index);
			if (quoted) {
				if (current == '"') {
					if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
						cell.append('"');
						index++;
					} else {
						quoted = false;
					}
				} else {
					cell.append(current);
				}
				continue;
			}
			if (current == '"') {
				quoted = true;
				continue;
			}
			if (current == ',') {
				row.add(cell.toString());
				cell.setLength(0);
				continue;
			}
			if (current == '\n' || current == '\r') {
				if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
					index++;
				}
				row.add(cell.toString());
				cell.setLength(0);
				rows.add(row);
				row = new ArrayList<>();
				continue;
			}
			cell.append(current);
		}
		if (quoted) {
			throw new InvalidShipmentFileException();
		}
		if (cell.length() > 0 || !row.isEmpty()) {
			row.add(cell.toString());
			rows.add(row);
		}
		return rows;
	}

	private static String quote(String value) {
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	public record RowFailure(int rowNumber, String reason) {
	}
}
