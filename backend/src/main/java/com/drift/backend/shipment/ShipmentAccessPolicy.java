package com.drift.backend.shipment;

import org.springframework.stereotype.Component;

import com.drift.backend.company.Company;

/** Defines company-based shipment view and edit permissions. */
@Component
public class ShipmentAccessPolicy {

	public boolean canView(Company activeCompany, Shipment shipment) {
		return activeCompany.getId().equals(shipment.getCompany().getId())
				|| (shipment.getImporterCompany() != null
						&& activeCompany.getId().equals(shipment.getImporterCompany().getId()));
	}

	public boolean canEdit(Company activeCompany, Shipment shipment) {
		return activeCompany.getId().equals(shipment.getCompany().getId());
	}
}
