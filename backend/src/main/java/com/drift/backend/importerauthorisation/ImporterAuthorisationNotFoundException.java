package com.drift.backend.importerauthorisation;

public class ImporterAuthorisationNotFoundException extends RuntimeException {

	public static final String MESSAGE = "Importer authorisation not found";

	public ImporterAuthorisationNotFoundException() {
		super(MESSAGE);
	}
}
