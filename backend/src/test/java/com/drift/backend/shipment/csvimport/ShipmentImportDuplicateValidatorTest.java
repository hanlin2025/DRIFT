package com.drift.backend.shipment.csvimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.drift.backend.company.Company;
import com.drift.backend.shipment.ShipmentRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShipmentImportDuplicateValidatorTest {

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private Company managingCompany;

    @Test
    void rejectsEveryCaseInsensitiveDuplicateWithinOneFile() {
        ShipmentImportDuplicateValidator validator = new ShipmentImportDuplicateValidator(shipmentRepository);
        ShipmentImportParseResult result = validator.validate(
                managingCompany,
                new ShipmentImportParseResult(List.of(row(2, "REF-1"), row(3, " ref-1 ")), List.of()));

        assertTrue(result.validRows().isEmpty());
        assertEquals(2, result.errors().size());
        assertTrue(result.errors().stream().allMatch(error -> error.code().equals("DUPLICATE_REFERENCE_IN_FILE")));
    }

    @Test
    void rejectsAReferenceThatAlreadyExistsForTheManagingCompany() {
        when(shipmentRepository.findExistingShipmentReferencesByCompanyIdIgnoringCase(any(), any()))
                .thenReturn(Set.of("ref-1"));
        ShipmentImportDuplicateValidator validator = new ShipmentImportDuplicateValidator(shipmentRepository);

        ShipmentImportParseResult result = validator.validate(
                managingCompany, new ShipmentImportParseResult(List.of(row(2, "REF-1")), List.of()));

        assertTrue(result.validRows().isEmpty());
        assertEquals("DUPLICATE_REFERENCE_IN_DATABASE", result.errors().getFirst().code());
        verify(shipmentRepository).findExistingShipmentReferencesByCompanyIdIgnoringCase(any(), eq(Set.of("ref-1")));
    }

    @Test
    void keepsAReferenceWhenItDoesNotExistForTheManagingCompany() {
        ShipmentImportDuplicateValidator validator = new ShipmentImportDuplicateValidator(shipmentRepository);

        ShipmentImportParseResult result = validator.validate(
                managingCompany, new ShipmentImportParseResult(List.of(row(2, "REF-1")), List.of()));

        assertEquals(1, result.validRows().size());
        assertTrue(result.errors().isEmpty());
    }

    private ShipmentImportRow row(long rowNumber, String reference) {
        return new ShipmentImportRow(
                rowNumber,
                reference,
                "Singapore",
                "Rotterdam",
                "Tanjung Pelepas",
                "Vessel A",
                OffsetDateTime.parse("2026-10-10T09:30:00+08:00"),
                "Feeder A",
                OffsetDateTime.parse("2026-10-10T15:45:00+08:00"),
                null);
    }
}
