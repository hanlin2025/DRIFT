package com.drift.backend.shipment.csvimport;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.company.ForwarderImporterRelationshipRepository;

/** Resolves the CSV importer company code without coupling CSV parsing to organisation authorization. */
@Component
public class ImporterOrganisationAuthorizer {

	private final CompanyRepository companies;
	private final ForwarderImporterRelationshipRepository relationships;

	public ImporterOrganisationAuthorizer(CompanyRepository companies, ForwarderImporterRelationshipRepository relationships) {
		this.companies = companies;
		this.relationships = relationships;
	}

	public ImporterOrganisationResolution resolveAuthorizedImporter(Company managingCompany, ShipmentImportRow row) {
		String companyCode = row.importerOrganisation();
		if (companyCode == null) {
			return ImporterOrganisationResolution.authorized(null);
		}

		Optional<Company> candidate = companies.findByCode(companyCode);
		if (candidate.isEmpty()) {
			return rejected(row, "UNKNOWN_IMPORTER_ORGANISATION", "Importer Organisation Company Code is unknown");
		}
		Company importer = candidate.get();
		if (!importer.isActive()) {
			return rejected(row, "INACTIVE_IMPORTER_ORGANISATION", "Importer Organisation Company Code is inactive");
		}
		if (managingCompany.getId().equals(importer.getId())
				|| !relationships.existsByForwarderCompanyIdAndImporterCompanyIdAndActiveTrue(
						managingCompany.getId(), importer.getId())) {
			return rejected(row, "UNAUTHORIZED_IMPORTER_ORGANISATION",
					"Your organisation is not authorized to import shipments for this importer");
		}
		return ImporterOrganisationResolution.authorized(importer);
	}

	private ImporterOrganisationResolution rejected(ShipmentImportRow row, String code, String message) {
		return ImporterOrganisationResolution.rejected(new ShipmentImportRowError(
				row.rowNumber(), ShipmentImportCsvContract.IMPORTER_ORGANISATION, code, message));
	}
}
