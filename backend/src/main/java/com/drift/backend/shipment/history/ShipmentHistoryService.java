package com.drift.backend.shipment.history;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.ShipmentAccessPolicy;
import com.drift.backend.shipment.ShipmentRepository;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

/** Reads a shipment's change history for users who can view that shipment, as the shipment detail does. */
@Service
public class ShipmentHistoryService {

	private final ShipmentRepository shipments;
	private final UserAccountRepository users;
	private final ShipmentAccessPolicy accessPolicy;
	private final ShipmentChangeEventRepository events;

	public ShipmentHistoryService(ShipmentRepository shipments, UserAccountRepository users,
			ShipmentAccessPolicy accessPolicy, ShipmentChangeEventRepository events) {
		this.shipments = shipments;
		this.users = users;
		this.accessPolicy = accessPolicy;
		this.events = events;
	}

	@Transactional(readOnly = true)
	public ShipmentHistoryResponse history(AuthenticatedUser principal, Long shipmentId) {
		Company company = activeCompany(principal);
		boolean visible = shipments.findByIdVisibleToCompanyId(shipmentId, company.getId())
				.filter(shipment -> accessPolicy.canView(company, shipment))
				.isPresent();
		if (!visible) {
			throw shipments.existsById(shipmentId)
					? new ShipmentDetailForbiddenException()
					: new ShipmentNotFoundException();
		}
		return ShipmentHistoryResponse.from(shipmentId,
				events.findByShipmentIdOrderByRecordedAtDescIdDesc(shipmentId));
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
