package com.drift.backend.account.authentication;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class CookieCsrfFilter extends OncePerRequestFilter {

	static final String HEADER = "X-DRIFT-Request";

	private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (SAFE.contains(request.getMethod()) || hasBearer(request) || !hasSessionCookie(request) || "1".equals(request.getHeader(HEADER))) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"message\":\"This request was rejected.\"}");
	}

	private static boolean hasBearer(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		return header != null && header.startsWith(SessionAuthenticationFilter.BEARER)
				&& !header.substring(SessionAuthenticationFilter.BEARER.length()).trim().isEmpty();
	}

	private static boolean hasSessionCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) return false;
		for (Cookie cookie : cookies) {
			if (SessionCookie.NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
				return true;
			}
		}
		return false;
	}
}
