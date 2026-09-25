package com.drift.backend.account.registration;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.drift.backend.account.exception.DuplicateAccountException;
import com.drift.backend.account.exception.InvalidRegistrationException;

@RestControllerAdvice(assignableTypes = RegistrationController.class)
public class RegistrationExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error ->
				errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return ResponseEntity.badRequest().body(body("Registration information is missing or invalid", errors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
		String detail = ex.getMessage() == null ? "" : ex.getMessage();
		if (detail.contains("Role")) {
			return ResponseEntity.badRequest().body(body(
					"Role must be IMPORTER or FREIGHT_FORWARDER",
					Map.of("role", "Role must be IMPORTER or FREIGHT_FORWARDER")));
		}
		return ResponseEntity.badRequest().body(body("Registration information is missing or invalid", Map.of()));
	}

	@ExceptionHandler(InvalidRegistrationException.class)
	public ResponseEntity<Map<String, Object>> handlePassword(InvalidRegistrationException ex) {
		return ResponseEntity.badRequest().body(body(ex.getMessage(), Map.of("password", ex.getMessage())));
	}

	@ExceptionHandler(DuplicateAccountException.class)
	public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateAccountException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	private static Map<String, Object> body(String message, Map<String, String> errors) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("message", message);
		body.put("errors", errors);
		return body;
	}
}
