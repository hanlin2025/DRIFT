package com.drift.backend.organisation;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.organisation.exception.InvalidOrganisationQueryException;
import com.drift.backend.organisation.exception.OrganisationAccessForbiddenException;

@RestControllerAdvice(assignableTypes = OrganisationController.class)
public class OrganisationExceptionHandler {

	@ExceptionHandler(InvalidOrganisationQueryException.class)
	public ResponseEntity<Map<String, String>> handleQuery(InvalidOrganisationQueryException ex) {
		return message(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, String>> handleType(MethodArgumentTypeMismatchException ex) {
		String message = "size".equals(ex.getName())
				? InvalidOrganisationQueryException.SIZE
				: InvalidOrganisationQueryException.PAGE;
		return message(HttpStatus.BAD_REQUEST, message);
	}

	@ExceptionHandler(OrganisationAccessForbiddenException.class)
	public ResponseEntity<Map<String, String>> handleForbidden(OrganisationAccessForbiddenException ex) {
		return message(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> handleSession(SessionEndedException ex) {
		return message(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	private static ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
	}
}
