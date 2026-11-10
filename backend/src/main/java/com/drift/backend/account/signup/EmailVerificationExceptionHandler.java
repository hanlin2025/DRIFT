package com.drift.backend.account.signup;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EmailVerificationController.class)
public class EmailVerificationExceptionHandler {

	static final String INVALID = "Verification information is missing or invalid";

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException ex) {
		if (ex.getBindingResult().getFieldError("token") != null) {
			// A malformed link is answered exactly like an unknown one.
			return invalidLink();
		}
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error ->
				errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return CompanySignupExceptionHandler.respond(HttpStatus.BAD_REQUEST, INVALID, errors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
		return CompanySignupExceptionHandler.respond(HttpStatus.BAD_REQUEST, INVALID, Map.of());
	}

	@ExceptionHandler(InvalidVerificationTokenException.class)
	public ResponseEntity<Map<String, Object>> handleToken(InvalidVerificationTokenException ex) {
		return invalidLink();
	}

	@ExceptionHandler(SignupRateLimitException.class)
	public ResponseEntity<Map<String, Object>> handleRateLimit(SignupRateLimitException ex) {
		return CompanySignupExceptionHandler.respond(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), Map.of());
	}

	private static ResponseEntity<Map<String, Object>> invalidLink() {
		return CompanySignupExceptionHandler.respond(HttpStatus.BAD_REQUEST, InvalidVerificationTokenException.MESSAGE,
				Map.of("token", InvalidVerificationTokenException.MESSAGE));
	}
}
