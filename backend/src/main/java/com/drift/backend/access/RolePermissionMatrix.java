package com.drift.backend.access;

import java.util.Optional;

import com.drift.backend.account.Role;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.organisation.exception.OrganisationAccessForbiddenException;
import com.drift.backend.shipment.csvimport.BulkImportForbiddenException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentLinkForbiddenException;

/**
 * Role gate for the APIs that already exist on main.
 * Company isolation for a particular shipment stays in {@code ShipmentAccessPolicy}:
 * a company can view a shipment it manages or one linked to it, and can edit only a shipment it manages.
 * {@link ShipmentVisibilityFilter} applies that view rule to the shipment list, detail, and tracking reads.
 * There is no planner role. Logistics managers keep the shipment operations main already allows,
 * which is also the role the open archive work permits to archive a shipment.
 */
public final class RolePermissionMatrix {

	private RolePermissionMatrix() {
	}

	public static Optional<String> denial(Role role, boolean activeCompany, String method, String path) {
		String normalized = normalize(path);
		if (normalized.equals("/api/admin") || normalized.startsWith("/api/admin/")) {
			if (role != Role.ADMIN) {
				return Optional.of(AssignmentForbiddenException.MESSAGE);
			}
			return Optional.empty();
		}
		if ("POST".equals(method) && normalized.equals("/api/shipments/import")) {
			return importUpload(role, activeCompany);
		}
		if ("GET".equals(method) && normalized.startsWith("/api/shipments/import/")) {
			if (!activeCompany) {
				return Optional.of(ShipmentAccessForbiddenException.MESSAGE);
			}
			return Optional.empty();
		}
		if ("PATCH".equals(method) && normalized.matches("/api/shipments/\\d+/link-importer")) {
			if (!activeCompany) {
				return Optional.of(ShipmentAccessForbiddenException.MESSAGE);
			}
			if (role != Role.FREIGHT_FORWARDER) {
				return Optional.of(ShipmentLinkForbiddenException.MESSAGE);
			}
			return Optional.empty();
		}
		if ("GET".equals(method) && normalized.equals("/api/organisations")) {
			if (!activeCompany) {
				return Optional.of(OrganisationAccessForbiddenException.MESSAGE);
			}
			return Optional.empty();
		}
		if (shipmentRead(method, normalized) || shipmentReplace(method, normalized)) {
			if (!activeCompany) {
				return Optional.of(ShipmentAccessForbiddenException.MESSAGE);
			}
			return Optional.empty();
		}
		if ("POST".equals(method) && normalized.equals("/api/shipments")) {
			if (!activeCompany) {
				return Optional.of(ShipmentCreationForbiddenException.MESSAGE);
			}
			return Optional.empty();
		}
		return Optional.empty();
	}

	private static Optional<String> importUpload(Role role, boolean activeCompany) {
		if (!activeCompany) {
			return Optional.of(BulkImportForbiddenException.ACTIVE_COMPANY);
		}
		if (role == Role.FREIGHT_FORWARDER) {
			return Optional.empty();
		}
		if (role == Role.ADMIN) {
			return Optional.of(BulkImportForbiddenException.ADMIN_DEFERRED);
		}
		return Optional.of(BulkImportForbiddenException.ROLE);
	}

	static boolean isShipmentRead(String method, String path) {
		return shipmentRead(method, normalize(path));
	}

	private static boolean shipmentRead(String method, String path) {
		return "GET".equals(method) && (path.equals("/api/shipments")
				|| path.matches("/api/shipments/\\d+")
				|| path.matches("/api/shipments/\\d+/tracking"));
	}

	private static boolean shipmentReplace(String method, String path) {
		return "PUT".equals(method) && path.matches("/api/shipments/\\d+");
	}

	static String normalize(String path) {
		if (path == null || path.isBlank()) {
			return "";
		}
		String value = path;
		int query = value.indexOf('?');
		if (query >= 0) {
			value = value.substring(0, query);
		}
		int semicolon = value.indexOf(';');
		if (semicolon >= 0) {
			value = value.substring(0, semicolon);
		}
		if (value.length() > 1 && value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}
}
