package com.drift.backend.shipment.csvimport;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.shipment.ShipmentService;

/** Creates one import candidate in its own transaction so a failed row cannot affect later rows. */
@Service
public class ShipmentImportShipmentCreator {

	private final CompanyRepository companies;
	private final UserAccountRepository users;
	private final ShipmentService shipments;

	public ShipmentImportShipmentCreator(CompanyRepository companies, UserAccountRepository users,
			ShipmentService shipments) {
		this.companies = companies;
		this.users = users;
		this.shipments = shipments;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void create(Long managingCompanyId, Long requestedByUserId, ShipmentImportRow row, Long importerCompanyId) {
		shipments.createImported(companies.getReferenceById(managingCompanyId), users.getReferenceById(requestedByUserId),
				row, importerCompanyId == null ? null : companies.getReferenceById(importerCompanyId));
	}
}
