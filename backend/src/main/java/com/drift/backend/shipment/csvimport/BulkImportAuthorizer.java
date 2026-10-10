package com.drift.backend.shipment.csvimport;

import org.springframework.stereotype.Component;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;

/** Authorizes the bulk-import role policy independently from the future upload endpoint. */
@Component
public class BulkImportAuthorizer {

	public void authorize(UserAccount account) {
		if (account.getCompany() == null || !account.getCompany().isActive()) {
			throw new BulkImportForbiddenException(BulkImportForbiddenException.ACTIVE_COMPANY);
		}
		if (account.getRole() == Role.FREIGHT_FORWARDER) {
			return;
		}
		if (account.getRole() == Role.ADMIN) {
			throw new BulkImportForbiddenException(BulkImportForbiddenException.ADMIN_DEFERRED);
		}
		throw new BulkImportForbiddenException(BulkImportForbiddenException.ROLE);
	}
}
