package com.drift.backend.shipment;

import org.springframework.stereotype.Component;

import com.drift.backend.company.Company;

/** Defines company-based shipment view and edit permissions. */
@Component
public class ShipmentAccessPolicy {

	public boolean canView(Company activeCompany, Shipment shipment) {
		Long importerCompanyId = shipment.getImporterCompany() == null
				? null
				: shipment.getImporterCompany().getId();
		return canView(activeCompany.getId(), shipment.getCompany().getId(), importerCompanyId);
	}

	/**
	 * A company can view a shipment it manages, or a shipment linked to it as the importer.
	 * Managing-company access stays, including a shipment an importer registered for their own company.
	 */
	public boolean canView(Long activeCompanyId, Long managingCompanyId, Long importerCompanyId) {
		return activeCompanyId.equals(managingCompanyId)
				|| (importerCompanyId != null && activeCompanyId.equals(importerCompanyId));
	}

	public boolean canEdit(Company activeCompany, Shipment shipment) {
		return activeCompany.getId().equals(shipment.getCompany().getId());
	}
}
