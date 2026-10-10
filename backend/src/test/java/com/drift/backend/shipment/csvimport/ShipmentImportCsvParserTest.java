package com.drift.backend.shipment.csvimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShipmentImportCsvParserTest {

    private final ShipmentImportCsvParser parser = new ShipmentImportCsvParser();

    @Test
    void parsesAndNormalizesValidRows() {
        ShipmentImportParseResult result = parse(csv(
                "  Ref-100  , Port of Singapore , Port of Rotterdam , Tanjung Pelepas , Vessel A ,"
                        + "2026-10-10T09:30:00+08:00, Feeder A ,2026-10-10T15:45:00+08:00,   "));

        assertTrue(result.errors().isEmpty());
        assertEquals(1, result.validRows().size());
        ShipmentImportRow parsed = result.validRows().getFirst();
        assertEquals("Ref-100", parsed.shipmentReference());
        assertEquals("Port of Singapore", parsed.origin());
        assertEquals("Vessel A", parsed.motherVessel());
        assertEquals("2026-10-10T09:30+08:00", parsed.plannedMotherArrivalAt().toString());
        assertNull(parsed.importerOrganisation());
    }

    @Test
    void preservesQuotedCommasAndOptionalImporterText() {
        ShipmentImportParseResult result = parse(csv(
                "REF-101,\"Port of Singapore, Singapore\",Rotterdam,Tanjung Pelepas,Vessel A,"
                        + "2026-10-10T09:30:00+08:00,Feeder A,2026-10-10T15:45:00+08:00, Importer One "));

        ShipmentImportRow parsed = result.validRows().getFirst();
        assertEquals("Port of Singapore, Singapore", parsed.origin());
        assertEquals("Importer One", parsed.importerOrganisation());
    }

    @Test
    void rejectsNonCanonicalHeadersAsFileLevelFailure() {
        ShipmentImportFileException exception = assertThrows(
                ShipmentImportFileException.class,
                () -> parse("Origin Port,Tracking/BL No.\nSingapore,REF-1\n"));

        assertTrue(exception.getMessage().contains("headers"));
    }

    @Test
    void keepsMixedValidAndInvalidRowsForPartialSuccess() {
        ShipmentImportParseResult result = parse(csv(validValues(),
                ",Singapore,Rotterdam,Tanjung Pelepas,Vessel B,2026-10-10T09:30:00+08:00,Feeder B,"
                        + "2026-10-10T15:45:00+08:00,"));

        assertEquals(1, result.validRows().size());
        assertEquals(1, result.errors().size());
        assertEquals(3, result.errors().getFirst().rowNumber());
        assertEquals("REQUIRED", result.errors().getFirst().code());
    }

    @Test
    void reportsLengthTimestampAndItineraryErrorsAtRowLevel() {
        String longReference = "R".repeat(101);
        ShipmentImportParseResult result = parse(csv(longReference
                + ",Singapore,Rotterdam,Tanjung Pelepas,Vessel A,2026-10-10T09:30:00,Feeder A,"
                + "2026-10-10T09:30:00+08:00,"));

        assertTrue(result.validRows().isEmpty());
        assertEquals(List.of("TOO_LONG", "INVALID_TIMESTAMP"),
                result.errors().stream().map(ShipmentImportRowError::code).toList());
    }

    @Test
    void rejectsEqualAndEarlierFeederDeparture() {
        ShipmentImportParseResult equalResult = parse(csv("REF-1,Singapore,Rotterdam,Tanjung,Vessel A,"
                + "2026-10-10T09:30:00+08:00,Feeder A,2026-10-10T09:30:00+08:00,"));
        ShipmentImportParseResult earlierResult = parse(csv("REF-2,Singapore,Rotterdam,Tanjung,Vessel A,"
                + "2026-10-10T09:30:00+08:00,Feeder A,2026-10-10T09:29:59+08:00,"));

        assertEquals("INVALID_ITINERARY_TIMING", equalResult.errors().getFirst().code());
        assertEquals("INVALID_ITINERARY_TIMING", earlierResult.errors().getFirst().code());
    }

    @Test
    void templateHeaderMatchesCanonicalBackendContract() throws IOException {
        Path template = Path.of("..", "frontend", "public", "shipment-import-template.csv");
        String header = Files.readAllLines(template).getFirst();

        assertEquals(ShipmentImportCsvContract.HEADERS, List.of(header.split(",", -1)));
    }

    @Test
    void rejectsMalformedUtf8AtFileLevel() {
        byte[] malformed = {(byte) 0xC3, (byte) 0x28};

        assertThrows(ShipmentImportFileException.class, () -> parser.parse(new ByteArrayInputStream(malformed)));
    }

    private ShipmentImportParseResult parse(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private String csv(String... rows) {
        return ShipmentImportCsvContract.HEADERS.stream().reduce((first, second) -> first + "," + second).orElseThrow()
                + "\n"
                + String.join("\n", rows)
                + "\n";
    }

    private String validValues() {
        return "REF-100,Singapore,Rotterdam,Tanjung Pelepas,Vessel A,2026-10-10T09:30:00+08:00,Feeder A,"
                + "2026-10-10T15:45:00+08:00,";
    }
}
