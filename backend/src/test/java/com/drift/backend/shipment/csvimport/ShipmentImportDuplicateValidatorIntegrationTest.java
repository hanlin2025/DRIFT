package com.drift.backend.shipment.csvimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drift.backend.company.Company;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentImportDuplicateValidatorIntegrationTest {

    @Autowired
    private ShipmentImportDuplicateValidator duplicateValidator;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appliesReferenceUniquenessWithinTheSuppliedManagingCompanyOnly() {
        Long firstCompanyId = companyId("HARBOURLINE_DEMO");
        Long secondCompanyId = companyId("STRAITS_FRESH_DEMO");
        insertShipment(firstCompanyId, "CSV-EXISTING-001");
        entityManager.clear();

        ShipmentImportParseResult sameCompany = duplicateValidator.validate(
                entityManager.getReference(Company.class, firstCompanyId),
                new ShipmentImportParseResult(List.of(row("csv-existing-001")), List.of()));
        ShipmentImportParseResult otherCompany = duplicateValidator.validate(
                entityManager.getReference(Company.class, secondCompanyId),
                new ShipmentImportParseResult(List.of(row("csv-existing-001")), List.of()));

        assertTrue(sameCompany.validRows().isEmpty());
        assertEquals("DUPLICATE_REFERENCE_IN_DATABASE", sameCompany.errors().getFirst().code());
        assertEquals(1, otherCompany.validRows().size());
        assertTrue(otherCompany.errors().isEmpty());
    }

    private void insertShipment(Long companyId, String reference) {
        String email = "csv-import-" + UUID.randomUUID() + "@example.com";
        jdbc.update("""
                INSERT INTO users (full_name, email, password_hash, role, company_id)
                VALUES ('CSV Import Test User', ?, 'not-used', 'FREIGHT_FORWARDER', ?)
                """, email, companyId);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        jdbc.update("""
                INSERT INTO shipments (
                    company_id, created_by_user_id, updated_by_user_id, shipment_reference,
                    origin, destination, transshipment_port, mother_vessel, planned_mother_arrival_at,
                    feeder_vessel, planned_feeder_departure_at, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'Singapore', 'Rotterdam', 'Tanjung Pelepas', 'Mother Vessel',
                    '2026-10-10T09:30:00+08:00', 'Feeder Vessel', '2026-10-10T15:45:00+08:00',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, companyId, userId, userId, reference);
    }

    private Long companyId(String code) {
        return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
    }

    private ShipmentImportRow row(String reference) {
        return new ShipmentImportRow(
                2,
                reference,
                "Singapore",
                "Rotterdam",
                "Tanjung Pelepas",
                "Mother Vessel",
                OffsetDateTime.parse("2026-10-10T09:30:00+08:00"),
                "Feeder Vessel",
                OffsetDateTime.parse("2026-10-10T15:45:00+08:00"),
                null);
    }
}
