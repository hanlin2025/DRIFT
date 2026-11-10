package com.drift.backend.account.authentication;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.AccountStatus;
import com.drift.backend.account.exception.SessionEndedException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class SessionAuthenticationFilter extends OncePerRequestFilter {

	public static final String BEARER = "Bearer ";

	private final JwtSessionTokens tokens;
	private final UserAccountRepository users;

	public SessionAuthenticationFilter(JwtSessionTokens tokens, UserAccountRepository users) {
		this.tokens = tokens;
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String presentedBearer = bearer(request);
		String token = presentedBearer != null ? presentedBearer : cookie(request);
		if (token == null) {
			chain.doFilter(request, response);
			return;
		}
		try {
			AuthenticatedUser user = tokens.parse(token);
			var state = users.findSessionStateById(user.id()).orElseThrow(SessionEndedException::new);
			// Only an ACTIVE account may use a session; a removed or unverified account is treated as signed out.
			if (state.sessionGeneration() != user.sessionGeneration() || state.status() != AccountStatus.ACTIVE) {
				throw new SessionEndedException();
			}
			var authentication = new UsernamePasswordAuthenticationToken(user, null,
					List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
			SecurityContextHolder.getContext().setAuthentication(authentication);
			chain.doFilter(request, response);
		} catch (SessionEndedException ex) {
			SecurityContextHolder.clearContext();
			// A password change invalidates the HttpOnly cookie before the browser can drop it.
			// Fresh login credentials must still be checked; protected routes keep rejecting it.
			if (presentedBearer == null && isCredentialLogin(request)) {
				chain.doFilter(request, response);
				return;
			}
			unauthorized(response, ex.getMessage());
		}
	}

	private static boolean isCredentialLogin(HttpServletRequest request) {
		if (!"POST".equalsIgnoreCase(request.getMethod())) return false;
		String path = request.getServletPath();
		if (path == null || path.isBlank()) path = request.getRequestURI();
		return "/api/login".equals(path);
	}

	private static String bearer(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER)) return null;
		String token = header.substring(BEARER.length()).trim();
		return token.isEmpty() ? null : token;
	}

	private static String cookie(HttpServletRequest request) {
		if (request.getCookies() == null) return null;
		for (var cookie : request.getCookies()) {
			if (SessionCookie.NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}

	public static void unauthorized(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"message\":\"" + message + "\"}");
	}
}
