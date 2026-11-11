package com.drift.backend.account.signup;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.drift.backend.account.exception.InvalidRegistrationException;
import com.drift.backend.organisation.exception.DuplicateOrganisationException;
import com.drift.backend.organisation.exception.InvalidOrganisationException;

@RestControllerAdvice(assignableTypes = CompanySignupController.class)
public class CompanySignupExceptionHandler {

	static final String INVALID = "Registration information is missing or invalid";

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error ->
				errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return respond(HttpStatus.BAD_REQUEST, INVALID, errors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
		return respond(HttpStatus.BAD_REQUEST, INVALID, Map.of());
	}

	@ExceptionHandler(InvalidOrganisationException.class)
	public ResponseEntity<Map<String, Object>> handleOrganisation(InvalidOrganisationException ex) {
		String field = InvalidOrganisationException.NAME.equals(ex.getMessage()) ? "companyName" : "companyCode";
		return respond(HttpStatus.BAD_REQUEST, INVALID, Map.of(field, ex.getMessage()));
	}

	@ExceptionHandler(InvalidRegistrationException.class)
	public ResponseEntity<Map<String, Object>> handlePassword(InvalidRegistrationException ex) {
		return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), Map.of("password", ex.getMessage()));
	}

	@ExceptionHandler(SignupRateLimitException.class)
	public ResponseEntity<Map<String, Object>> handleRateLimit(SignupRateLimitException ex) {
		return respond(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), Map.of());
	}

	@ExceptionHandler(DuplicateOrganisationException.class)
	public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateOrganisationException ex) {
		return respond(HttpStatus.CONFLICT, ex.getMessage(), Map.of("companyCode", ex.getMessage()));
	}

	static ResponseEntity<Map<String, Object>> respond(HttpStatus status, String message, Map<String, String> errors) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("message", message);
		body.put("errors", errors);
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
	}
}
