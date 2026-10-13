package com.drift.backend.account.profile;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.drift.backend.account.exception.InvalidProfileException;
import com.drift.backend.account.exception.ProfileForbiddenException;
import com.drift.backend.account.exception.SessionEndedException;

@RestControllerAdvice(assignableTypes = ProfileController.class)
public class ProfileExceptionHandler {

	@ExceptionHandler(InvalidProfileException.class)
	public ResponseEntity<Map<String, String>> handleInvalid(InvalidProfileException ex) {
		return message(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
		return message(HttpStatus.BAD_REQUEST, InvalidProfileException.MISSING);
	}

	@ExceptionHandler(ProfileForbiddenException.class)
	public ResponseEntity<Map<String, String>> handleForbidden(ProfileForbiddenException ex) {
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
