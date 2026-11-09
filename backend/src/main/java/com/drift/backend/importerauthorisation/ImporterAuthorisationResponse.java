package com.drift.backend.importerauthorisation;

import com.drift.backend.organisation.OrganisationResponse;

public record ImporterAuthorisationResponse(Long id, OrganisationResponse importer, boolean active) {
}
