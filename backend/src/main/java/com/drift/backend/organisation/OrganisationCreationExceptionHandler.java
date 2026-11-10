package com.drift.backend.organisation;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.organisation.exception.DuplicateOrganisationException;
import com.drift.backend.organisation.exception.InvalidOrganisationException;
import com.drift.backend.organisation.exception.OrganisationCreationForbiddenException;

@RestControllerAdvice(assignableTypes = OrganisationCreationController.class)
public class OrganisationCreationExceptionHandler {

	@ExceptionHandler(InvalidOrganisationException.class)
	public ResponseEntity<Map<String, String>> handleInvalid(InvalidOrganisationException ex) {
		return message(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
		return message(HttpStatus.BAD_REQUEST, InvalidOrganisationException.CODE);
	}

	@ExceptionHandler(AssignmentForbiddenException.class)
	public ResponseEntity<Map<String, String>> handleForbidden(AssignmentForbiddenException ex) {
		return message(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	@ExceptionHandler(OrganisationCreationForbiddenException.class)
	public ResponseEntity<Map<String, String>> handleInactive(OrganisationCreationForbiddenException ex) {
		return message(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> handleSession(SessionEndedException ex) {
		return message(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	@ExceptionHandler(DuplicateOrganisationException.class)
	public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateOrganisationException ex) {
		return message(HttpStatus.CONFLICT, ex.getMessage());
	}

	private static ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
	}
}
