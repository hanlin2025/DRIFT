package com.drift.backend.shipment.csvimport;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.company.ForwarderImporterRelationshipRepository;
import com.drift.backend.shipment.ShipmentService;

/** Creates one import candidate in its own transaction so a failed row cannot affect later rows. */
@Service
public class ShipmentImportShipmentCreator {

	private final CompanyRepository companies;
	private final UserAccountRepository users;
	private final ShipmentService shipments;
	private final ForwarderImporterRelationshipRepository relationships;

	public ShipmentImportShipmentCreator(CompanyRepository companies, UserAccountRepository users,
			ShipmentService shipments, ForwarderImporterRelationshipRepository relationships) {
		this.companies = companies;
		this.users = users;
		this.shipments = shipments;
		this.relationships = relationships;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void create(Long managingCompanyId, Long requestedByUserId, ShipmentImportRow row, Long importerCompanyId) {
		if (importerCompanyId != null) {
			boolean authorised = relationships.findByPairForUpdate(managingCompanyId, importerCompanyId)
					.map(relationship -> relationship.isActive())
					.orElse(false);
			if (!authorised) {
				throw new ImporterAuthorisationRevokedException();
			}
		}
		shipments.createImported(companies.getReferenceById(managingCompanyId), users.getReferenceById(requestedByUserId),
				row, importerCompanyId == null ? null : companies.getReferenceById(importerCompanyId));
	}
}
