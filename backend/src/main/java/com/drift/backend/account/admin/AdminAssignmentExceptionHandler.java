package com.drift.backend.account.admin;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.drift.backend.account.admin.exception.AdminAssignmentConflictException;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.admin.exception.AssignedUserNotFoundException;
import com.drift.backend.account.admin.exception.InvalidAssignmentException;
import com.drift.backend.account.exception.SessionEndedException;

@RestControllerAdvice(assignableTypes = AdminAssignmentController.class)
public class AdminAssignmentExceptionHandler {

	static final String UNREADABLE = "Assignment information is missing or invalid";

	@ExceptionHandler(InvalidAssignmentException.class)
	public ResponseEntity<Map<String, String>> handleInvalid(InvalidAssignmentException ex) {
		return message(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
		return message(HttpStatus.BAD_REQUEST, UNREADABLE);
	}

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

	@ExceptionHandler(AdminAssignmentConflictException.class)
	public ResponseEntity<Map<String, String>> handleConflict(AdminAssignmentConflictException ex) {
		return message(HttpStatus.CONFLICT, ex.getMessage());
	}

	private static ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
	}
}
