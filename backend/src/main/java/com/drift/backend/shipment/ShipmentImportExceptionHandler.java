package com.drift.backend.shipment;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.InvalidShipmentFileException;
import com.drift.backend.shipment.exception.ShipmentImportForbiddenException;
import com.drift.backend.shipment.exception.ShipmentImportNotFoundException;

@RestControllerAdvice(assignableTypes = ShipmentImportController.class)
public class ShipmentImportExceptionHandler {

	@ExceptionHandler({ InvalidShipmentFileException.class, MissingServletRequestPartException.class })
	public ResponseEntity<Map<String, String>> handleInvalidFile() {
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(Map.of("message", InvalidShipmentFileException.MESSAGE));
	}

	@ExceptionHandler(ShipmentImportForbiddenException.class)
	public ResponseEntity<Map<String, String>> handleForbidden(ShipmentImportForbiddenException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}

	@ExceptionHandler({ ShipmentImportNotFoundException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<Map<String, String>> handleNotFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ShipmentImportNotFoundException.MESSAGE));
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> handleSession(SessionEndedException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}
}
