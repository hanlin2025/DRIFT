package com.drift.backend.shipment;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentConstraintEditForbiddenException;
import com.drift.backend.shipment.exception.ShipmentConstraintPortConflictException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotEditableException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

import tools.jackson.databind.JsonNode;

@Service
public class ShipmentConstraintService {

	private final ShipmentRepository shipments;
	private final ShipmentImporterConstraintRepository constraints;
	private final UserAccountRepository users;
	private final ShipmentAccessPolicy accessPolicy;
	private final ShipmentDetailAssembler details;

	ShipmentConstraintService(ShipmentRepository shipments, ShipmentImporterConstraintRepository constraints,
			UserAccountRepository users, ShipmentAccessPolicy accessPolicy, ShipmentDetailAssembler details) {
		this.shipments = shipments;
		this.constraints = constraints;
		this.users = users;
		this.accessPolicy = accessPolicy;
		this.details = details;
	}

	@Transactional
	public ShipmentDetailResponse update(AuthenticatedUser principal, Long shipmentId, JsonNode body) {
		ShipmentConstraintRequest request = ShipmentConstraintRequest.parse(body);
		UserAccount account = activeAccount(principal);
		Shipment shipment = shipments.findByIdForUpdate(shipmentId).orElseThrow(ShipmentNotFoundException::new);
		if (!accessPolicy.canView(account.getCompany(), shipment)) {
			throw new ShipmentDetailForbiddenException();
		}
		if (!currentImporter(account.getCompany(), shipment)) {
			throw new ShipmentConstraintEditForbiddenException();
		}
		if (shipment.getStatus() != ShipmentStatus.ACTIVE) {
			throw new ShipmentNotEditableException();
		}
		String currentPort = shipment.getTransshipmentPort().strip();
		if (!currentPort.equals(request.expectedPort())) {
			throw new ShipmentConstraintPortConflictException();
		}
		Long importerCompanyId = shipment.getImporterCompany().getId();
		if (request.deadline() == null) {
			constraints.findByShipmentIdAndImporterCompanyId(shipment.getId(), importerCompanyId)
					.ifPresent(constraints::delete);
		} else {
			confirm(shipment.getId(), importerCompanyId, currentPort, request.deadline());
		}
		constraints.flush();
		return details.detail(shipment);
	}

	private void confirm(Long shipmentId, Long importerCompanyId, String currentPort, OffsetDateTime deadline) {
		ShipmentImporterConstraint constraint = constraints
				.findByShipmentIdAndImporterCompanyId(shipmentId, importerCompanyId)
				.orElseGet(() -> new ShipmentImporterConstraint(shipmentId, importerCompanyId, currentPort, deadline));
		constraint.confirm(currentPort, deadline);
		constraints.save(constraint);
	}

	private static boolean currentImporter(Company company, Shipment shipment) {
		return shipment.getImporterCompany() != null && shipment.getImporterCompany().getId() != null
				&& company.getId().equals(shipment.getImporterCompany().getId());
	}

	private UserAccount activeAccount(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentAccessForbiddenException();
		}
		return account;
	}
}
