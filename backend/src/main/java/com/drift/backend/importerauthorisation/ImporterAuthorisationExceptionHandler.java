package com.drift.backend.importerauthorisation;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;

@RestControllerAdvice(assignableTypes = ImporterAuthorisationController.class)
public class ImporterAuthorisationExceptionHandler {

	@ExceptionHandler(InvalidImporterOrganisationException.class)
	public ResponseEntity<Map<String, String>> handleInvalid(InvalidImporterOrganisationException exception) {
		return message(HttpStatus.BAD_REQUEST, exception.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleUnreadable() {
		return message(HttpStatus.BAD_REQUEST, InvalidImporterOrganisationException.MESSAGE);
	}

	@ExceptionHandler(ImporterAuthorisationForbiddenException.class)
	public ResponseEntity<Map<String, String>> handleForbidden(ImporterAuthorisationForbiddenException exception) {
		return message(HttpStatus.FORBIDDEN, exception.getMessage());
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> handleSession(SessionEndedException exception) {
		return message(HttpStatus.UNAUTHORIZED, exception.getMessage());
	}

	@ExceptionHandler({ ImporterAuthorisationNotFoundException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<Map<String, String>> handleNotFound() {
		return message(HttpStatus.NOT_FOUND, ImporterAuthorisationNotFoundException.MESSAGE);
	}

	private static ResponseEntity<Map<String, String>> message(HttpStatus status, String text) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", text));
	}
}
