package com.drift.backend.account.passwordreset;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
public class PasswordResetController {

	private final PasswordResetService passwordResetService;

	public PasswordResetController(PasswordResetService passwordResetService) {
		this.passwordResetService = passwordResetService;
	}

	@PostMapping("/api/auth/forgot-password")
	public ResponseEntity<ForgotPasswordResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
			HttpServletRequest http) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(passwordResetService.request(request, clientAddress(http)));
	}

	@PostMapping("/api/auth/reset-password")
	public ResponseEntity<ResetPasswordResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
			HttpServletRequest http) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(passwordResetService.reset(request, clientAddress(http)));
	}

	static String clientAddress(HttpServletRequest request) {
		String address = request.getRemoteAddr();
		if (address == null || address.isBlank()) {
			return "unknown";
		}
		return address;
	}
}
