package com.drift.backend.cdg201;

/**
 * Display-only MPA location-code reference metadata. It is not a port, berth,
 * vessel-visit, or shipment-location assertion.
 */
public record Cdg201MpaLocationReference(
        String rawLocationCode,
        String sourceApi,
        String sourceField,
        String officialDescription,
        String derivedCategory,
        String mappingStatus,
        String referenceProvenance,
        String limitation) {
}
