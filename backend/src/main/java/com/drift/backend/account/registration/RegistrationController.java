package com.drift.backend.account.registration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.drift.backend.account.registration.RegisterRequest;
import com.drift.backend.account.registration.RegisterResponse;
import com.drift.backend.account.registration.invitation.InvitationRequest;
import com.drift.backend.account.registration.invitation.InvitationResponse;
import jakarta.validation.Valid;

@RestController
public class RegistrationController {
	private final RegistrationService registrationService;

	public RegistrationController(RegistrationService registrationService) {
		this.registrationService = registrationService;
	}

	@PostMapping("/api/invitations/resolve")
	public ResponseEntity<InvitationResponse> resolve(@Valid @RequestBody InvitationRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(registrationService.resolveInvitation(request.token()));
	}

	@PostMapping("/api/register")
	public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(registrationService.register(request));
	}
}