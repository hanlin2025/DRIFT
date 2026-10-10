package com.drift.backend.access;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Applies {@link RolePermissionMatrix} after the session filter and before the controllers. */
public class RolePermissionFilter extends OncePerRequestFilter {

	private final UserAccountRepository users;

	public RolePermissionFilter(UserAccountRepository users) {
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
			chain.doFilter(request, response);
			return;
		}
		UserAccount account = users.findForPermissionCheck(principal.id()).orElse(null);
		if (account == null) {
			forbidden(response, HttpServletResponse.SC_UNAUTHORIZED, SessionEndedException.MESSAGE);
			return;
		}
		Company company = account.getCompany();
		boolean activeCompany = company != null && company.isActive();
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		Optional<String> denial = RolePermissionMatrix.denial(account.getRole(), activeCompany, request.getMethod(), path);
		if (denial.isEmpty()) {
			chain.doFilter(request, response);
			return;
		}
		forbidden(response, HttpServletResponse.SC_FORBIDDEN, denial.get());
	}

	private static void forbidden(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"message\":\"" + message + "\"}");
	}
}
