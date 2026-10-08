package com.drift.backend.shipment;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.drift.backend.shipment.exception.InvalidShipmentFileException;

@RestControllerAdvice
public class UploadSizeExceptionHandler {

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Map<String, String>> handleTooLarge() {
		return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
				.body(Map.of("message", InvalidShipmentFileException.MESSAGE));
	}
}
