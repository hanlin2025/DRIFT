package com.drift.backend.maritimeai;

/** Server-captured importer provenance for one complete assessment row. */
record MaritimeAiAssessmentHistoryScope(int version, Long importerCompanyId) {
	static final int CURRENT_VERSION = 1;

	static MaritimeAiAssessmentHistoryScope captured(Long importerCompanyId) {
		return new MaritimeAiAssessmentHistoryScope(CURRENT_VERSION, importerCompanyId);
	}

	boolean identifiesImporter(Long companyId) {
		return version == CURRENT_VERSION && importerCompanyId != null && importerCompanyId.equals(companyId);
	}
}
