package com.drift.backend.account.setup;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
public class SetupController {

	static final String CREDENTIAL_HEADER = "X-DRIFT-Setup-Token";

	private final SetupService setupService;

	public SetupController(SetupService setupService) {
		this.setupService = setupService;
	}

	@GetMapping("/api/setup/status")
	public ResponseEntity<SetupStatusResponse> status(
			@RequestHeader(value = CREDENTIAL_HEADER, required = false) String credential) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(setupService.status(credential));
	}

	@PostMapping("/api/setup/administrator")
	public ResponseEntity<SetupAccountResponse> create(
			@RequestHeader(value = CREDENTIAL_HEADER, required = false) String credential,
			@Valid @RequestBody SetupAdministratorRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(setupService.create(credential, request));
	}
}
