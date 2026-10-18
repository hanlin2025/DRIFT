package com.drift.backend.account.admin;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.drift.backend.account.admin.exception.AccountAlreadyRemovedException;
import com.drift.backend.account.admin.exception.AssignedUserNotFoundException;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.exception.SessionEndedException;

@RestControllerAdvice(assignableTypes = AccountRemovalController.class)
public class AccountRemovalExceptionHandler {

	@ExceptionHandler(AssignmentForbiddenException.class)
	public ResponseEntity<Map<String, String>> handleForbidden(AssignmentForbiddenException ex) {
		return message(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> handleSession(SessionEndedException ex) {
		return message(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	@ExceptionHandler({ AssignedUserNotFoundException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<Map<String, String>> handleMissing(Exception ex) {
		return message(HttpStatus.NOT_FOUND, AssignedUserNotFoundException.MESSAGE);
	}

	@ExceptionHandler(AccountAlreadyRemovedException.class)
	public ResponseEntity<Map<String, String>> handleConflict(AccountAlreadyRemovedException ex) {
		return message(HttpStatus.CONFLICT, ex.getMessage());
	}

	private static ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
	}
}
