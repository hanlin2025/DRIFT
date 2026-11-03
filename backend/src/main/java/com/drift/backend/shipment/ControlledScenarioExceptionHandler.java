package com.drift.backend.shipment;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.ControlledScenarioAccessDeniedException;
import com.drift.backend.shipment.exception.ControlledScenarioInvalidRequestException;
import com.drift.backend.shipment.exception.ControlledScenarioUnavailableException;
import com.drift.backend.shipment.exception.ScenarioSessionNotFoundException;
import com.drift.backend.shipment.exception.ScenarioTransitionConflictException;
import com.drift.backend.maritimeai.ScenarioMaritimeAiAssessmentController;

@RestControllerAdvice(assignableTypes = { ControlledScenarioSessionController.class, ScenarioMaritimeAiAssessmentController.class })
public class ControlledScenarioExceptionHandler {
	@ExceptionHandler(ScenarioSessionNotFoundException.class)
	public ResponseEntity<ControlledScenarioApiError> notFound(ScenarioSessionNotFoundException exception) {
		return error(HttpStatus.NOT_FOUND, "SCENARIO_NOT_FOUND", exception.getMessage(), false);
	}

	@ExceptionHandler(ControlledScenarioAccessDeniedException.class)
	public ResponseEntity<ControlledScenarioApiError> denied(ControlledScenarioAccessDeniedException exception) {
		return error(HttpStatus.FORBIDDEN, ControlledScenarioAccessDeniedException.CODE, exception.getMessage(), false);
	}

	@ExceptionHandler(ControlledScenarioUnavailableException.class)
	public ResponseEntity<ControlledScenarioApiError> unavailable(ControlledScenarioUnavailableException exception) {
		return error(HttpStatus.valueOf(exception.getStatus()), exception.getCode(), exception.getMessage(), false);
	}

	@ExceptionHandler(ScenarioTransitionConflictException.class)
	public ResponseEntity<ControlledScenarioApiError> conflict(ScenarioTransitionConflictException exception) {
		return error(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage(), false);
	}

	@ExceptionHandler(ControlledScenarioInvalidRequestException.class)
	public ResponseEntity<ControlledScenarioApiError> invalid(ControlledScenarioInvalidRequestException exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), false);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException exception) {
		Map<String, String> fields = new LinkedHashMap<>();
		exception.getBindingResult().getFieldErrors().forEach(error ->
				fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", "INVALID_REQUEST");
		body.put("message", "The scenario request is invalid.");
		body.put("retryable", false);
		body.put("fieldErrors", fields);
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(body);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ControlledScenarioApiError> unreadable(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The scenario request is invalid.", false);
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<ControlledScenarioApiError> unauthenticated(SessionEndedException exception) {
		return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", exception.getMessage(), false);
	}

	private static ResponseEntity<ControlledScenarioApiError> error(HttpStatus status, String code,
			String message, boolean retryable) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
				.body(new ControlledScenarioApiError(code, message, retryable));
	}
}
