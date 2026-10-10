package com.drift.backend.shipment;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentLinkForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

@RestControllerAdvice(assignableTypes = ShipmentLinkController.class)
public class ShipmentLinkExceptionHandler {

	@ExceptionHandler(InvalidImporterOrganisationException.class)
	public ResponseEntity<Map<String, String>> handleInvalid(InvalidImporterOrganisationException ex) {
		return message(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
		return message(HttpStatus.BAD_REQUEST, "Shipment information is missing or invalid");
	}

	@ExceptionHandler({ ShipmentLinkForbiddenException.class, ShipmentAccessForbiddenException.class })
	public ResponseEntity<Map<String, String>> handleForbidden(RuntimeException ex) {
		return message(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> handleSession(SessionEndedException ex) {
		return message(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	@ExceptionHandler({ ShipmentNotFoundException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<Map<String, String>> handleNotFound() {
		return message(HttpStatus.NOT_FOUND, ShipmentNotFoundException.MESSAGE);
	}

	private static ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
	}
}
