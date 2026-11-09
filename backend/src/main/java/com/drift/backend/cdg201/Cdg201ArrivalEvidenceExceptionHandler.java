package com.drift.backend.cdg201;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

@RestControllerAdvice(assignableTypes = Cdg201ArrivalEvidenceController.class)
public class Cdg201ArrivalEvidenceExceptionHandler {

	@ExceptionHandler(ShipmentDetailForbiddenException.class)
	ResponseEntity<Map<String, String>> handleForbidden(ShipmentDetailForbiddenException exception) {
		return message(HttpStatus.FORBIDDEN, exception.getMessage());
	}

	@ExceptionHandler(SessionEndedException.class)
	ResponseEntity<Map<String, String>> handleSession(SessionEndedException exception) {
		return message(HttpStatus.UNAUTHORIZED, exception.getMessage());
	}

	@ExceptionHandler({ ShipmentNotFoundException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<Map<String, String>> handleNotFound() {
		return message(HttpStatus.NOT_FOUND, ShipmentNotFoundException.MESSAGE);
	}

	private static ResponseEntity<Map<String, String>> message(HttpStatus status, String text) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", text));
	}
}
