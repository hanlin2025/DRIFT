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
import com.drift.backend.account.exception.SessionEndedException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class SessionAuthenticationFilter extends OncePerRequestFilter {

	static final String BEARER = "Bearer ";

	private final JwtSessionTokens tokens;
	private final UserAccountRepository users;

	public SessionAuthenticationFilter(JwtSessionTokens tokens, UserAccountRepository users) {
		this.tokens = tokens;
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER)) {
			chain.doFilter(request, response);
			return;
		}
		try {
			AuthenticatedUser user = tokens.parse(header.substring(BEARER.length()).trim());
			long generation = users.findSessionGenerationById(user.id()).orElseThrow(SessionEndedException::new);
			if (generation != user.sessionGeneration()) {
				throw new SessionEndedException();
			}
			var authentication = new UsernamePasswordAuthenticationToken(user, null,
					List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
			SecurityContextHolder.getContext().setAuthentication(authentication);
			chain.doFilter(request, response);
		} catch (SessionEndedException ex) {
			SecurityContextHolder.clearContext();
			unauthorized(response, ex.getMessage());
		}
	}

	public static void unauthorized(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"message\":\"" + message + "\"}");
	}
}
