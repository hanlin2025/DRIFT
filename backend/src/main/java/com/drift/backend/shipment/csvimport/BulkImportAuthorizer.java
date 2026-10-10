package com.drift.backend.shipment.csvimport;

import org.springframework.stereotype.Component;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;

/** Authorizes the bulk-import role policy independently from the future upload endpoint. */
@Component
public class BulkImportAuthorizer {

	public void authorize(UserAccount account) {
		if (account.getCompany() == null || !account.getCompany().isActive()) {
			throw new BulkImportForbiddenException("Bulk import requires an active company");
		}
		if (account.getRole() == Role.FREIGHT_FORWARDER) {
			return;
		}
		if (account.getRole() == Role.ADMIN) {
			throw new BulkImportForbiddenException(
					"ADMIN bulk-import access is deferred until company type is defined");
		}
		throw new BulkImportForbiddenException("Your role is not authorized to import shipments in bulk");
	}
}
