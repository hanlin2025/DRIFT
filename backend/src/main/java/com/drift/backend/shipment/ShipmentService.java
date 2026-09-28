package com.drift.backend.shipment;

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
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;

@Service
public class ShipmentService {

	private static final String REFERENCE_UNIQUE_CONSTRAINT = "shipments_company_reference_key";

	private final ShipmentRepository shipments;
	private final UserAccountRepository users;

	public ShipmentService(ShipmentRepository shipments, UserAccountRepository users) {
		this.shipments = shipments;
		this.users = users;
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
}
