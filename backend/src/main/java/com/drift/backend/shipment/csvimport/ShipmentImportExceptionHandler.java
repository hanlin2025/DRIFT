package com.drift.backend.shipment.csvimport;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;

@RestControllerAdvice(assignableTypes = ShipmentImportController.class)
public class ShipmentImportExceptionHandler {

	@ExceptionHandler({ InvalidShipmentImportUploadException.class, MaxUploadSizeExceededException.class })
	public ResponseEntity<Map<String, String>> invalidUpload(RuntimeException exception) {
		String message = exception instanceof InvalidShipmentImportUploadException
				? exception.getMessage()
				: "CSV upload must not exceed 5 MB";
		return response(HttpStatus.BAD_REQUEST, message);
	}

	@ExceptionHandler(BulkImportForbiddenException.class)
	public ResponseEntity<Map<String, String>> bulkImportForbidden(BulkImportForbiddenException exception) {
		return response(HttpStatus.FORBIDDEN, exception.getMessage());
	}

	@ExceptionHandler(ShipmentAccessForbiddenException.class)
	public ResponseEntity<Map<String, String>> jobAccessForbidden(ShipmentAccessForbiddenException exception) {
		return response(HttpStatus.FORBIDDEN, exception.getMessage());
	}

	@ExceptionHandler(SessionEndedException.class)
	public ResponseEntity<Map<String, String>> sessionEnded(SessionEndedException exception) {
		return response(HttpStatus.UNAUTHORIZED, exception.getMessage());
	}

	@ExceptionHandler({ ShipmentImportJobNotFoundException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<Map<String, String>> notFound() {
		return response(HttpStatus.NOT_FOUND, ShipmentImportJobNotFoundException.MESSAGE);
	}

	private static ResponseEntity<Map<String, String>> response(HttpStatus status, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
	}
}
