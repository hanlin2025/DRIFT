package com.drift.backend.shipment.csvimport;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Separates valid import candidates from row-level failures to allow partial success. */
public record ShipmentImportParseResult(List<ShipmentImportRow> validRows, List<ShipmentImportRowError> errors) {

    public ShipmentImportParseResult {
        validRows = List.copyOf(validRows);
        errors = List.copyOf(errors);
    }

    public ShipmentImportParseResult withAdditionalErrors(Collection<ShipmentImportRowError> additionalErrors) {
        if (additionalErrors.isEmpty()) {
            return this;
        }

        Set<Long> invalidRows = new HashSet<>();
        additionalErrors.forEach(error -> invalidRows.add(error.rowNumber()));

        List<ShipmentImportRow> remainingRows = validRows.stream()
                .filter(row -> !invalidRows.contains(row.rowNumber()))
                .toList();
        List<ShipmentImportRowError> combinedErrors = new java.util.ArrayList<>(errors);
        combinedErrors.addAll(additionalErrors);
        return new ShipmentImportParseResult(remainingRows, combinedErrors);
    }
}
