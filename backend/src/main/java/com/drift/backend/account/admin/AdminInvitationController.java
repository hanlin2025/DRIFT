package com.drift.backend.account.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;

import jakarta.validation.Valid;

@RestController
public class AdminInvitationController {

	private final AdminInvitationService invitations;

	public AdminInvitationController(AdminInvitationService invitations) {
		this.invitations = invitations;
	}

	@PostMapping("/api/admin/invitations")
	public ResponseEntity<InvitationIssuedResponse> invite(
			@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody InviteColleagueRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(invitations.invite(user, request));
	}
}
