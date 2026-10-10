package com.drift.backend.shipment.csvimport;

import com.drift.backend.company.Company;

/** The result of resolving and authorizing an optional importer organisation for one import row. */
public record ImporterOrganisationResolution(Company importerCompany, ShipmentImportRowError error) {

	public static ImporterOrganisationResolution authorized(Company importerCompany) {
		return new ImporterOrganisationResolution(importerCompany, null);
	}

	public static ImporterOrganisationResolution rejected(ShipmentImportRowError error) {
		return new ImporterOrganisationResolution(null, error);
	}

	public boolean isAuthorized() {
		return error == null;
	}
}
