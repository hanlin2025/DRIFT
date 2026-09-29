package com.drift.backend.shipment;

import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;
import com.drift.backend.shipment.exception.InvalidItineraryException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

@Service
public class ShipmentService {

	private static final String REFERENCE_UNIQUE_CONSTRAINT = "shipments_company_reference_key";

	private final ShipmentRepository shipments;
	private final UserAccountRepository users;

	public ShipmentService(ShipmentRepository shipments, UserAccountRepository users) {
		this.shipments = shipments;
		this.users = users;
	}

	@Transactional(readOnly = true)
	public List<ShipmentResponse> list(AuthenticatedUser principal) {
		Company company = activeCompany(principal);
		return shipments.findByCompanyIdOrderByCreatedAtDesc(company.getId()).stream()
				.map(ShipmentResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public ShipmentResponse get(AuthenticatedUser principal, Long shipmentId) {
		Company company = activeCompany(principal);
		return shipments.findByIdAndCompanyId(shipmentId, company.getId())
				.map(ShipmentResponse::from)
				.orElseThrow(ShipmentNotFoundException::new);
	}

	@Transactional
	public ShipmentResponse create(AuthenticatedUser principal, CreateShipmentRequest request) {
		if (!request.plannedFeederDepartureAt().isAfter(request.plannedMotherArrivalAt())) {
			throw new InvalidItineraryException();
		}

		UserAccount creator = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = creator.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentCreationForbiddenException();
		}

		String shipmentReference = request.shipmentReference().strip();
		if (shipments.existsByCompanyIdAndShipmentReferenceIgnoreCase(company.getId(), shipmentReference)) {
			throw new DuplicateShipmentReferenceException();
		}

		Shipment shipment = new Shipment(company, creator, shipmentReference, request.origin().strip(),
				request.destination().strip(), request.motherVessel().strip(), request.plannedMotherArrivalAt(),
				request.feederVessel().strip(), request.plannedFeederDepartureAt());
		try {
			return ShipmentResponse.from(shipments.saveAndFlush(shipment));
		} catch (DataIntegrityViolationException ex) {
			if (isDuplicateReference(ex)) {
				throw new DuplicateShipmentReferenceException();
			}
			throw ex;
		}
	}

	private static boolean isDuplicateReference(DataIntegrityViolationException ex) {
		Throwable cause = ex.getCause();
		while (cause != null) {
			if (cause instanceof ConstraintViolationException violation
					&& REFERENCE_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	private Company activeCompany(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentAccessForbiddenException();
		}
		return company;
	}
}
