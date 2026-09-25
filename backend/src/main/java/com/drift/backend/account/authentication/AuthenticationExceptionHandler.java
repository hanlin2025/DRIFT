package com.drift.backend.account.authentication;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.drift.backend.account.exception.InvalidCredentialsException;
import com.drift.backend.account.exception.SessionEndedException;

@RestControllerAdvice(assignableTypes = AuthenticationController.class)
public class AuthenticationExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error ->
				errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(body("Login information is missing or invalid", errors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(body("Login information is missing or invalid", Map.of()));
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	public ResponseEntity<Map<String, String>> handleCredentials(InvalidCredentialsException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> handleSession(SessionEndedException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}

	private static Map<String, Object> body(String message, Map<String, String> errors) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("message", message);
		body.put("errors", errors);
		return body;
	}
}
