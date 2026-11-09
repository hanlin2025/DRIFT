package com.drift.backend.account.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.exception.DuplicateAccountException;
import com.drift.backend.account.exception.SessionEndedException;

@RestControllerAdvice(assignableTypes = AdminInvitationController.class)
public class AdminInvitationExceptionHandler {

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> ended(SessionEndedException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(AssignmentForbiddenException.class)
	public ResponseEntity<Map<String, String>> forbidden(AssignmentForbiddenException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(InvitationManagementForbiddenException.class)
	public ResponseEntity<Map<String, String>> cannotManage(InvitationManagementForbiddenException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(NoEligibleOrganisationException.class)
	public ResponseEntity<Map<String, String>> noOrganisation(NoEligibleOrganisationException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(DuplicateAccountException.class)
	public ResponseEntity<Map<String, String>> duplicate(DuplicateAccountException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(InvitationConflictException.class)
	public ResponseEntity<Map<String, String>> conflict(InvitationConflictException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(InvitationDeliveryException.class)
	public ResponseEntity<Map<String, String>> delivery(InvitationDeliveryException ex) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(InvalidInvitationException.class)
	public ResponseEntity<Map<String, Object>> invalid(InvalidInvitationException ex) {
		return ResponseEntity.badRequest().body(body(ex.getMessage(), Map.of(ex.getField(), ex.getMessage())));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return ResponseEntity.badRequest().body(body("Invitation information is missing or invalid", errors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest().body(body("Invitation information is missing or invalid", Map.of()));
	}

	private static Map<String, Object> body(String message, Map<String, String> errors) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("message", message);
		body.put("errors", errors);
		return body;
	}
}
