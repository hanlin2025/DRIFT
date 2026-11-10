package com.drift.backend.account.authentication;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.exception.SessionEndedException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
public class AuthenticationController {

	private final AuthenticationService authenticationService;
	private final boolean secureCookie;

	public AuthenticationController(AuthenticationService authenticationService,
			@Value("${app.session.cookie.secure:false}") boolean secureCookie) {
		this.authenticationService = authenticationService;
		this.secureCookie = secureCookie;
	}

	@PostMapping("/api/login")
	public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		LoginResponse body = authenticationService.login(request);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.SET_COOKIE, SessionCookie.set(body.token(), body.expiresAt(), Instant.now(), secure(http)))
				.body(body);
	}

	@PostMapping("/api/logout")
	public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest http) {
		if (user == null) throw new SessionEndedException();
		authenticationService.logout(user);
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, SessionCookie.clear(secure(http)))
				.build();
	}

	private boolean secure(HttpServletRequest request) {
		return secureCookie || request.isSecure();
	}

	@GetMapping("/api/session")
	public ResponseEntity<SessionView> session(@AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authenticationService.current(user));
	}
}
