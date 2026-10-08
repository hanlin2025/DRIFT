package com.drift.backend.shipment;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentLinkForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

@RestControllerAdvice(assignableTypes = ShipmentImporterController.class)
public class ImporterLinkExceptionHandler {

	@ExceptionHandler({ ShipmentLinkForbiddenException.class, ShipmentAccessForbiddenException.class })
	public ResponseEntity<Map<String, String>> handleForbidden(RuntimeException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler({ ShipmentNotFoundException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<Map<String, String>> handleNotFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ShipmentNotFoundException.MESSAGE));
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> handleSession(SessionEndedException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}
}
