package com.drift.backend.shipment;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;

@RestControllerAdvice
public class InvalidImporterExceptionHandler {

	@ExceptionHandler(InvalidImporterOrganisationException.class)
	public ResponseEntity<Map<String, String>> handleInvalid(InvalidImporterOrganisationException ex) {
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(Map.of("message", ex.getMessage()));
	}
}
