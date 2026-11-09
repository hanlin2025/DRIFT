package com.drift.backend.account.setup;

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

@RestControllerAdvice(assignableTypes = SetupController.class)
public class SetupExceptionHandler {

	@ExceptionHandler(SetupUnavailableException.class)
	public ResponseEntity<Map<String, String>> unavailable(SetupUnavailableException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(SetupCredentialException.class)
	public ResponseEntity<Map<String, String>> credential(SetupCredentialException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(SetupAlreadyCompletedException.class)
	public ResponseEntity<Map<String, String>> completed(SetupAlreadyCompletedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(DuplicateAccountException.class)
	public ResponseEntity<Map<String, String>> duplicate(DuplicateAccountException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(InvalidSetupException.class)
	public ResponseEntity<Map<String, Object>> invalid(InvalidSetupException ex) {
		return ResponseEntity.badRequest().body(body(ex.getMessage(), Map.of(ex.getField(), ex.getMessage())));
	}

	@ExceptionHandler(InvalidRegistrationException.class)
	public ResponseEntity<Map<String, Object>> password(InvalidRegistrationException ex) {
		return ResponseEntity.badRequest().body(body(ex.getMessage(), Map.of("password", ex.getMessage())));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return ResponseEntity.badRequest().body(body("Setup information is missing or invalid", errors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest().body(body("Setup information is missing or invalid", Map.of()));
	}

	private static Map<String, Object> body(String message, Map<String, String> errors) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("message", message);
		body.put("errors", errors);
		return body;
	}
}
