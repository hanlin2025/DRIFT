package com.drift.backend.access;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.ShipmentAccessPolicy;
import com.drift.backend.shipment.ShipmentVisibility;
import com.drift.backend.shipment.ShipmentVisibilityLookup;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Applies the merged shipment view rule to the reads an importer can make.
 * A company can view a shipment it manages or a shipment whose linked importer is that company.
 * Detail is refused before the handler runs, so the detail document — including the risk field
 * the open shipment-detail work adds — is not written for a company that cannot view it.
 * Tracking keeps the current not-found response for both a missing shipment and a hidden one.
 * List responses that already contain only visible shipments are passed through unchanged.
 */
public class ShipmentVisibilityFilter extends OncePerRequestFilter {

	private final UserAccountRepository users;
	private final ShipmentVisibilityLookup shipments;
	private final ShipmentAccessPolicy access;
	private final ObjectMapper json;

	public ShipmentVisibilityFilter(UserAccountRepository users, ShipmentVisibilityLookup shipments,
			ShipmentAccessPolicy access, ObjectMapper json) {
		this.users = users;
		this.shipments = shipments;
		this.access = access;
		this.json = json;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = path(request);
		if (!RolePermissionMatrix.isShipmentRead(request.getMethod(), path)) {
			chain.doFilter(request, response);
			return;
		}
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
			chain.doFilter(request, response);
			return;
		}
		UserAccount account = users.findForPermissionCheck(principal.id()).orElse(null);
		if (account == null) {
			deny(response, HttpServletResponse.SC_UNAUTHORIZED, SessionEndedException.MESSAGE);
			return;
		}
		Long companyId = activeCompanyId(account);
		if (companyId == null) {
			chain.doFilter(request, response);
			return;
		}
		Long shipmentId = shipmentId(path);
		if (path.matches("/api/shipments/\\d+/tracking")) {
			if (shipmentId == null) {
				chain.doFilter(request, response);
				return;
			}
			enforceTracking(request, response, chain, companyId, shipmentId);
			return;
		}
		if (path.matches("/api/shipments/\\d+")) {
			if (shipmentId == null) {
				chain.doFilter(request, response);
				return;
			}
			enforceDetail(request, response, chain, companyId, shipmentId);
			return;
		}
		enforceList(request, response, chain, companyId);
	}

	private void enforceDetail(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			long companyId, long shipmentId) throws ServletException, IOException {
		Optional<ShipmentVisibility> row = shipments.find(shipmentId);
		if (row.isEmpty() || canView(companyId, row.get())) {
			chain.doFilter(request, response);
			return;
		}
		deny(response, HttpServletResponse.SC_FORBIDDEN, ShipmentDetailForbiddenException.MESSAGE);
	}

	private void enforceTracking(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			long companyId, long shipmentId) throws ServletException, IOException {
		Optional<ShipmentVisibility> row = shipments.find(shipmentId);
		if (row.isPresent() && canView(companyId, row.get())) {
			chain.doFilter(request, response);
			return;
		}
		deny(response, HttpServletResponse.SC_NOT_FOUND, ShipmentNotFoundException.MESSAGE);
	}

	private void enforceList(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			long companyId) throws ServletException, IOException {
		ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
		byte[] filtered = null;
		try {
			chain.doFilter(request, wrapper);
			if (wrapper.getStatus() == HttpServletResponse.SC_OK) {
				filtered = withoutHiddenShipments(wrapper.getContentAsByteArray(), companyId);
			}
		} finally {
			if (filtered != null) {
				replaceBody(wrapper, filtered);
			}
			wrapper.copyBodyToResponse();
		}
	}

	private boolean canView(long companyId, ShipmentVisibility shipment) {
		return access.canView(companyId, shipment.managingCompanyId(), shipment.importerCompanyId());
	}

	private byte[] withoutHiddenShipments(byte[] body, long companyId) {
		if (body == null || body.length == 0) {
			return null;
		}
		JsonNode root;
		try {
			root = json.readTree(body);
		} catch (JacksonException ex) {
			return null;
		}
		if (!(root instanceof ArrayNode rows) || rows.isEmpty()) {
			return null;
		}
		List<Long> ids = new ArrayList<>();
		for (JsonNode row : rows) {
			Long id = idOf(row);
			if (id != null) {
				ids.add(id);
			}
		}
		if (ids.isEmpty()) {
			return null;
		}
		Set<Long> visible = shipments.visibleIds(companyId, ids);
		boolean hidden = false;
		ArrayNode kept = json.createArrayNode();
		for (JsonNode row : rows) {
			Long id = idOf(row);
			if (id != null && !visible.contains(id)) {
				hidden = true;
				continue;
			}
			kept.add(row);
		}
		if (!hidden) {
			return null;
		}
		return json.writeValueAsBytes(kept);
	}

	private static Long idOf(JsonNode row) {
		if (row == null || !row.isObject()) {
			return null;
		}
		JsonNode id = row.get("id");
		if (id == null || !id.isIntegralNumber()) {
			return null;
		}
		try {
			return id.longValue();
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static void replaceBody(ContentCachingResponseWrapper wrapper, byte[] body) throws IOException {
		wrapper.resetBuffer();
		try {
			wrapper.getOutputStream().write(body);
		} catch (IllegalStateException ex) {
			wrapper.getWriter().write(new String(body, StandardCharsets.UTF_8));
		}
	}

	private static Long activeCompanyId(UserAccount account) {
		Company company = account.getCompany();
		if (company == null || !company.isActive() || company.getId() == null) {
			return null;
		}
		return company.getId();
	}

	private static Long shipmentId(String path) {
		if (!path.startsWith("/api/shipments/")) {
			return null;
		}
		String rest = path.substring("/api/shipments/".length());
		int slash = rest.indexOf('/');
		String digits = slash < 0 ? rest : rest.substring(0, slash);
		try {
			return Long.parseLong(digits);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private void deny(HttpServletResponse response, int status, String message) throws IOException {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("message", message);
		response.setStatus(status);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(json.writeValueAsString(body));
	}

	private static String path(HttpServletRequest request) {
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path != null && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		return RolePermissionMatrix.normalize(path);
	}
}
