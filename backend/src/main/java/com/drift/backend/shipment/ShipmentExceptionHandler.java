package com.drift.backend.shipment;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;
import com.drift.backend.shipment.exception.InvalidItineraryException;
import com.drift.backend.shipment.exception.InvalidShipmentRequestException;
import com.drift.backend.shipment.exception.InvalidShipmentIdException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentAlreadyActiveException;
import com.drift.backend.shipment.exception.ShipmentAlreadyArchivedException;
import com.drift.backend.shipment.exception.ShipmentArchiveForbiddenException;
import com.drift.backend.shipment.exception.ShipmentRestoreForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotEditableException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.drift.backend.shipment.exception.StaleShipmentVersionException;

@RestControllerAdvice(assignableTypes = ShipmentController.class)
public class ShipmentExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(error ->
				errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(body("Shipment information is missing or invalid", errors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(body("Shipment information is missing or invalid", Map.of()));
	}

	@ExceptionHandler(InvalidItineraryException.class)
	public ResponseEntity<Map<String, Object>> handleInvalidItinerary(InvalidItineraryException ex) {
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(body(ex.getMessage(), Map.of("plannedFeederDepartureAt", ex.getMessage())));
	}

	@ExceptionHandler(InvalidShipmentRequestException.class)
	public ResponseEntity<Map<String, Object>> handleInvalidShipmentRequest(InvalidShipmentRequestException ex) {
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(body(ex.getMessage(), ex.getErrors()));
	}

	@ExceptionHandler(DuplicateShipmentReferenceException.class)
	public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateShipmentReferenceException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(StaleShipmentVersionException.class)
	public ResponseEntity<Map<String, String>> handleStaleVersion(StaleShipmentVersionException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(ShipmentCreationForbiddenException.class)
	public ResponseEntity<Map<String, String>> handleForbidden(ShipmentCreationForbiddenException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler({
			ShipmentAccessForbiddenException.class,
			ShipmentDetailForbiddenException.class,
			ShipmentArchiveForbiddenException.class,
			ShipmentRestoreForbiddenException.class,
			ShipmentNotEditableException.class
	})
	public ResponseEntity<Map<String, String>> handleAccessForbidden(RuntimeException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler(ShipmentAlreadyArchivedException.class)
	public ResponseEntity<Map<String, String>> handleAlreadyArchived() {
		return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ShipmentAlreadyArchivedException.MESSAGE));
	}

	@ExceptionHandler(ShipmentAlreadyActiveException.class)
	public ResponseEntity<Map<String, String>> handleAlreadyActive() {
		return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ShipmentAlreadyActiveException.MESSAGE));
	}

	@ExceptionHandler(ShipmentNotFoundException.class)
	public ResponseEntity<Map<String, String>> handleNotFound() {
		return notFound();
	}

	private ResponseEntity<Map<String, String>> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ShipmentNotFoundException.MESSAGE));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
			HttpServletRequest request) {
		if (LocalDate.class.equals(exception.getRequiredType())) {
			return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
					.body(body("Shipment information is missing or invalid",
							Map.of(exception.getName(), "Arrival date must use ISO format YYYY-MM-DD")));
		}
		if (ShipmentStatus.class.equals(exception.getRequiredType())) {
			return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
					.body(body("Shipment information is missing or invalid",
							Map.of(exception.getName(), "Status must be ACTIVE or ARCHIVED")));
		}
		if ("shipmentId".equals(exception.getName()) && "PATCH".equals(request.getMethod())
				&& request.getRequestURI() != null && request.getRequestURI().endsWith("/status")) {
			return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
					.body(Map.of("message", InvalidShipmentIdException.MESSAGE));
		}
		return notFound();
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
