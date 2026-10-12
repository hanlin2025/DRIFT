package com.drift.backend.shipment;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Reads the managing company and linked importer for a shipment without going through
 * {@link ShipmentRepository}. The visibility rule matches
 * {@code findVisibleByCompanyIdOrderByCreatedAtDesc} and {@code findByIdVisibleToCompanyId}.
 */
@Service
public class ShipmentVisibilityLookup {

	@PersistenceContext
	private EntityManager entityManager;

	@Transactional(readOnly = true)
	public Optional<ShipmentVisibility> find(long shipmentId) {
		List<Object[]> rows = entityManager.createQuery("""
				SELECT shipment.id, company.id, importer.id
				FROM Shipment shipment
				JOIN shipment.company company
				LEFT JOIN shipment.importerCompany importer
				WHERE shipment.id = :id
				""", Object[].class)
				.setParameter("id", shipmentId)
				.getResultList();
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		Object[] row = rows.get(0);
		Long importerCompanyId = row[2] == null ? null : ((Number) row[2]).longValue();
		return Optional.of(new ShipmentVisibility(
				((Number) row[0]).longValue(),
				((Number) row[1]).longValue(),
				importerCompanyId));
	}

	@Transactional(readOnly = true)
	public Set<Long> visibleIds(long companyId, Collection<Long> shipmentIds) {
		if (shipmentIds == null || shipmentIds.isEmpty()) {
			return Set.of();
		}
		List<Long> ids = entityManager.createQuery("""
				SELECT shipment.id
				FROM Shipment shipment
				JOIN shipment.company company
				LEFT JOIN shipment.importerCompany importer
				WHERE shipment.id IN :ids
					AND (company.id = :companyId OR importer.id = :companyId)
				""", Long.class)
				.setParameter("ids", shipmentIds)
				.setParameter("companyId", companyId)
				.getResultList();
		return new HashSet<>(ids);
	}
}
