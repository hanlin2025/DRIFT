package com.drift.backend.cdg201;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Deliberately tiny replay lookup. The full official export is not bundled
 * until its redistribution terms are approved. Unknown codes remain unknown.
 */
@Component
public class Cdg201MpaLocationReferenceLookup {

    private static final String OFFICIAL_PROVENANCE = "MPA OCEANS-X Location Codes v1.0.0; reference retrieved 2026-10-27; exact code-string match only";
    private static final Map<String, Reference> KNOWN = Map.of(
            "PEBGA", new Reference("PILOT EAST BOARD GRD A", "PILOT_GROUND"));

    public Cdg201MpaLocationReference lookup(String rawCode, String sourceApi, String sourceField) {
        Reference reference = KNOWN.get(rawCode);
        if (reference == null) {
            return new Cdg201MpaLocationReference(rawCode, sourceApi, sourceField, "", "UNKNOWN",
                    "UNKNOWN", "No official Location Codes match in the reviewed reference export",
                    "No description is inferred. API field namespace compatibility, port relationship, and vessel visit remain unverified.");
        }
        return new Cdg201MpaLocationReference(rawCode, sourceApi, sourceField, reference.description(), reference.category(),
                "PARTIAL", OFFICIAL_PROVENANCE,
                "Derived category is display-only. The official reference does not establish that this vessel API field shares its namespace, a port/berth, or a vessel visit.");
    }

    private record Reference(String description, String category) {
    }
}
