package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.exception.InvalidShipmentFileException;

class ShipmentCsvTest {

	@Test
	void readsQuotedCommasQuotesAndBlankRows() {
		String csv = ShipmentCsv.template()
				+ "HBL-1,\"Shanghai, CN\",\"Jakarta, ID\",Singapore,\"MV \"\"Pacific\"\"\",2026-10-15T08:00:00+08:00,MV Strait Runner,2026-10-16T12:00:00+08:00,Straits Fresh Imports (Demo)\n"
				+ ",,,,,,,,\n"
				+ "HBL-2,Busan,Singapore,Singapore,MV Northern Light,2026-10-15T08:00:00+08:00,MV Harbour Link,2026-10-16T12:00:00+08:00,\n";

		var rows = ShipmentCsv.parse("\uFEFF" + csv);

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).number()).isEqualTo(2);
		assertThat(rows.get(0).get("Origin Port")).isEqualTo("Shanghai, CN");
		assertThat(rows.get(0).get("Mother Vessel")).isEqualTo("MV \"Pacific\"");
		assertThat(rows.get(0).get("Importer Organisation")).isEqualTo("Straits Fresh Imports (Demo)");
		assertThat(rows.get(1).number()).isEqualTo(4);
		assertThat(rows.get(1).get("Importer Organisation")).isEmpty();
	}

	@Test
	void rejectsAFileThatDoesNotUseTheTemplate() {
		assertThatThrownBy(() -> ShipmentCsv.parse("Tracking/BL No.,Origin\nHBL-1,Shanghai\n"))
				.isInstanceOf(InvalidShipmentFileException.class)
				.hasMessage(InvalidShipmentFileException.MESSAGE);
		assertThatThrownBy(() -> ShipmentCsv.parse(""))
				.isInstanceOf(InvalidShipmentFileException.class);
		assertThatThrownBy(() -> ShipmentCsv.parse(ShipmentCsv.template() + "one,two,three,four,five,six,seven,eight,nine,extra\n"))
				.isInstanceOf(InvalidShipmentFileException.class);
	}

	@Test
	void rejectsMoreThanFiveHundredDataRows() {
		StringBuilder csv = new StringBuilder(ShipmentCsv.template());
		for (int row = 0; row < ShipmentCsv.MAX_ROWS + 1; row++) {
			csv.append("HBL-").append(row).append(",A,B,C,D,2026-10-15T08:00:00+08:00,E,2026-10-16T12:00:00+08:00,\n");
		}
		assertThatThrownBy(() -> ShipmentCsv.parse(csv.toString()))
				.isInstanceOf(InvalidShipmentFileException.class);
	}

	@Test
	void quotesReasonsInTheErrorReport() {
		String report = ShipmentCsv.errorReport(java.util.List.of(
				new ShipmentCsv.RowFailure(2, ShipmentCsv.DUPLICATE_REFERENCE)));
		assertThat(report).isEqualTo("Row,Reason\n2,\"" + ShipmentCsv.DUPLICATE_REFERENCE + "\"\n");
	}
}
