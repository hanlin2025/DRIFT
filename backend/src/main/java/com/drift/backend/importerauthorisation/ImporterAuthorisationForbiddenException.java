package com.drift.backend.importerauthorisation;

public class ImporterAuthorisationForbiddenException extends RuntimeException {

	public static final String ACTIVE_COMPANY = "Importer authorisations require an active company";
	public static final String VIEW = "Only an administrator or freight forwarder of this company can view importer authorisations";
	public static final String CHANGE = "Only an administrator of this company can change importer authorisations";

	public ImporterAuthorisationForbiddenException(String message) {
		super(message);
	}
}
