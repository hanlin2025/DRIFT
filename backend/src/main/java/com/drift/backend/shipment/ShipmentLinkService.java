package com.drift.backend.shipment;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentLinkForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

import jakarta.persistence.EntityManager;

@Service
public class ShipmentLinkService {

	private final ShipmentRepository shipments;
	private final UserAccountRepository users;
	private final CompanyRepository companies;
	private final ShipmentConnectionRisk connectionRisk;
	private final EntityManager entityManager;

	public ShipmentLinkService(ShipmentRepository shipments, UserAccountRepository users, CompanyRepository companies,
			ShipmentConnectionRisk connectionRisk, EntityManager entityManager) {
		this.shipments = shipments;
		this.users = users;
		this.companies = companies;
		this.connectionRisk = connectionRisk;
		this.entityManager = entityManager;
	}

	@Transactional
	public ShipmentDetailResponse link(AuthenticatedUser principal, Long shipmentId, Long importerCompanyId) {
		UserAccount account = forwarder(principal);
		Long ownerId = owningCompanyId(shipmentId);
		if (!ownerId.equals(account.getCompany().getId())) {
			throw new ShipmentLinkForbiddenException();
		}
		Shipment shipment = shipments.findById(shipmentId).orElseThrow(ShipmentNotFoundException::new);
		shipment.linkImporter(importer(importerCompanyId, ownerId));
		shipment.markUpdated(account, Instant.now());
		return detail(shipments.saveAndFlush(shipment));
	}

	private UserAccount forwarder(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentAccessForbiddenException();
		}
		if (account.getRole() != Role.FREIGHT_FORWARDER) {
			throw new ShipmentLinkForbiddenException();
		}
		return account;
	}

	private Long owningCompanyId(Long shipmentId) {
		return entityManager.createQuery("""
				select shipment.company.id from Shipment shipment where shipment.id = :id
				""", Long.class)
				.setParameter("id", shipmentId)
				.getResultStream()
				.findFirst()
				.orElseThrow(ShipmentNotFoundException::new);
	}

	private Company importer(Long importerCompanyId, Long ownerId) {
		if (importerCompanyId == null) {
			return null;
		}
		return companies.findById(importerCompanyId)
				.filter(Company::isActive)
				.filter(company -> !company.getId().equals(ownerId))
				.orElseThrow(InvalidImporterOrganisationException::new);
	}

	private ShipmentDetailResponse detail(Shipment shipment) {
		ShipmentConnectionRisk.Snapshot connection = connectionRisk.assess(shipment);
		return ShipmentDetailResponse.from(shipment, connection.planned(), connection.remaining(),
				connection.motherVesselPosition(), connection.feederVesselPosition(), connection.arrivalComparison());
	}
}
