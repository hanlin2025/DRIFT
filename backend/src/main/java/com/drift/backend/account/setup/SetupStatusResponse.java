package com.drift.backend.account.setup;

import java.util.List;

public record SetupStatusResponse(boolean initialized, List<SetupOrganisationResponse> organisations) {
}
