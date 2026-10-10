package com.drift.backend.shipment.csvimport;

import com.drift.backend.company.Company;
import com.drift.backend.shipment.ShipmentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Applies the current company-scoped shipment-reference uniqueness rule to parsed rows. */
@Component
public class ShipmentImportDuplicateValidator {

    private final ShipmentRepository shipmentRepository;

    public ShipmentImportDuplicateValidator(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    public ShipmentImportParseResult validate(Company managingCompany, ShipmentImportParseResult parseResult) {
        ShipmentImportParseResult withoutInFileDuplicates =
                parseResult.withAdditionalErrors(findInFileDuplicateErrors(parseResult.validRows()));
        return withoutInFileDuplicates.withAdditionalErrors(
                findExistingShipmentDuplicateErrors(managingCompany, withoutInFileDuplicates.validRows()));
    }

    private List<ShipmentImportRowError> findInFileDuplicateErrors(List<ShipmentImportRow> rows) {
        Map<String, List<ShipmentImportRow>> rowsByReference = new LinkedHashMap<>();
        for (ShipmentImportRow row : rows) {
            rowsByReference
                    .computeIfAbsent(normalizeReference(row.shipmentReference()), ignored -> new ArrayList<>())
                    .add(row);
        }

        List<ShipmentImportRowError> errors = new ArrayList<>();
        rowsByReference.values().stream()
                .filter(duplicateRows -> duplicateRows.size() > 1)
                .flatMap(List::stream)
                .forEach(row -> errors.add(new ShipmentImportRowError(
                        row.rowNumber(),
                        ShipmentImportCsvContract.TRACKING_BL_NUMBER,
                        "DUPLICATE_REFERENCE_IN_FILE",
                        "Tracking/BL No. is duplicated within this CSV file")));
        return errors;
    }

    private List<ShipmentImportRowError> findExistingShipmentDuplicateErrors(
            Company managingCompany, List<ShipmentImportRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> references = rows.stream()
                .map(ShipmentImportRow::shipmentReference)
                .map(this::normalizeReference)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> existingReferences = shipmentRepository
                .findExistingShipmentReferencesByCompanyIdIgnoringCase(managingCompany.getId(), references);
        List<ShipmentImportRowError> errors = new ArrayList<>();
        for (ShipmentImportRow row : rows) {
            if (existingReferences.contains(normalizeReference(row.shipmentReference()))) {
                errors.add(new ShipmentImportRowError(
                        row.rowNumber(),
                        ShipmentImportCsvContract.TRACKING_BL_NUMBER,
                        "DUPLICATE_REFERENCE_IN_DATABASE",
                        "Tracking/BL No. already exists for the managing organisation"));
            }
        }
        return errors;
    }

    private String normalizeReference(String reference) {
        return reference.trim().toLowerCase(Locale.ROOT);
    }
}
