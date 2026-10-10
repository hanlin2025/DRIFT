package com.drift.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.drift.backend.account.Role;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.organisation.exception.OrganisationAccessForbiddenException;
import com.drift.backend.shipment.csvimport.BulkImportForbiddenException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentLinkForbiddenException;

class RolePermissionMatrixTest {

	@Test
	void reservesAssignmentForAdministrators() {
		assertThat(RolePermissionMatrix.denial(Role.ADMIN, true, "GET", "/api/admin/users")).isEmpty();
		assertThat(RolePermissionMatrix.denial(Role.ADMIN, true, "PATCH", "/api/admin/users/4/assign")).isEmpty();
		assertThat(RolePermissionMatrix.denial(Role.FREIGHT_FORWARDER, true, "GET", "/api/admin/roles"))
				.contains(AssignmentForbiddenException.MESSAGE);
		assertThat(RolePermissionMatrix.denial(Role.IMPORTER, true, "GET", "/api/admin/organisations/"))
				.contains(AssignmentForbiddenException.MESSAGE);
		assertThat(RolePermissionMatrix.denial(Role.LOGISTICS_MANAGER, true, "PATCH", "/api/admin/users/4/assign"))
				.contains(AssignmentForbiddenException.MESSAGE);
	}

	@Test
	void keepsImportUploadOnTheExistingFreightForwarderRule() {
		assertThat(RolePermissionMatrix.denial(Role.FREIGHT_FORWARDER, true, "POST", "/api/shipments/import")).isEmpty();
		assertThat(RolePermissionMatrix.denial(Role.FREIGHT_FORWARDER, false, "POST", "/api/shipments/import"))
				.contains(BulkImportForbiddenException.ACTIVE_COMPANY);
		assertThat(RolePermissionMatrix.denial(Role.ADMIN, true, "POST", "/api/shipments/import"))
				.contains(BulkImportForbiddenException.ADMIN_DEFERRED);
		assertThat(RolePermissionMatrix.denial(Role.LOGISTICS_MANAGER, true, "POST", "/api/shipments/import"))
				.contains(BulkImportForbiddenException.ROLE);
		assertThat(RolePermissionMatrix.denial(Role.IMPORTER, true, "POST", "/api/shipments/import"))
				.contains(BulkImportForbiddenException.ROLE);
	}

	@Test
	void requiresAnActiveCompanyToReadAnImportJob() {
		assertThat(RolePermissionMatrix.denial(Role.IMPORTER, true, "GET", "/api/shipments/import/9")).isEmpty();
		assertThat(RolePermissionMatrix.denial(Role.IMPORTER, true, "GET", "/api/shipments/import/9/errors")).isEmpty();
		assertThat(RolePermissionMatrix.denial(Role.FREIGHT_FORWARDER, false, "GET", "/api/shipments/import/9/errors"))
				.contains(ShipmentAccessForbiddenException.MESSAGE);
	}

	@Test
	void limitsLinkingToAFreightForwarderWithAnActiveCompany() {
		assertThat(RolePermissionMatrix.denial(Role.FREIGHT_FORWARDER, true, "PATCH", "/api/shipments/12/link-importer"))
				.isEmpty();
		assertThat(RolePermissionMatrix.denial(Role.ADMIN, true, "PATCH", "/api/shipments/12/link-importer"))
				.contains(ShipmentLinkForbiddenException.MESSAGE);
		assertThat(RolePermissionMatrix.denial(Role.IMPORTER, true, "PATCH", "/api/shipments/12/link-importer"))
				.contains(ShipmentLinkForbiddenException.MESSAGE);
		assertThat(RolePermissionMatrix.denial(Role.LOGISTICS_MANAGER, true, "PATCH", "/api/shipments/12/link-importer"))
				.contains(ShipmentLinkForbiddenException.MESSAGE);
		assertThat(RolePermissionMatrix.denial(Role.FREIGHT_FORWARDER, false, "PATCH", "/api/shipments/12/link-importer"))
				.contains(ShipmentAccessForbiddenException.MESSAGE);
	}

	@Test
	void requiresAnActiveCompanyForOrganisationSearchAndShipmentReads() {
		assertThat(RolePermissionMatrix.denial(Role.IMPORTER, false, "GET", "/api/organisations"))
				.contains(OrganisationAccessForbiddenException.MESSAGE);
		assertThat(RolePermissionMatrix.denial(Role.LOGISTICS_MANAGER, true, "GET", "/api/organisations?type=importer"))
				.isEmpty();
		assertThat(RolePermissionMatrix.denial(Role.IMPORTER, false, "GET", "/api/shipments"))
				.contains(ShipmentAccessForbiddenException.MESSAGE);
		assertThat(RolePermissionMatrix.denial(Role.ADMIN, false, "GET", "/api/shipments/3"))
				.contains(ShipmentAccessForbiddenException.MESSAGE);
		assertThat(RolePermissionMatrix.denial(Role.FREIGHT_FORWARDER, false, "GET", "/api/shipments/3/tracking"))
				.contains(ShipmentAccessForbiddenException.MESSAGE);
		assertThat(RolePermissionMatrix.denial(Role.LOGISTICS_MANAGER, false, "PUT", "/api/shipments/3"))
				.contains(ShipmentAccessForbiddenException.MESSAGE);
	}

	@Test
	void leavesShipmentWritesOpenToEveryRoleThatAlreadyHasAnActiveCompany() {
		for (Role role : Role.values()) {
			assertThat(RolePermissionMatrix.denial(role, true, "POST", "/api/shipments")).isEmpty();
			assertThat(RolePermissionMatrix.denial(role, true, "PUT", "/api/shipments/8")).isEmpty();
			assertThat(RolePermissionMatrix.denial(role, true, "GET", "/api/shipments/8/tracking")).isEmpty();
		}
		assertThat(RolePermissionMatrix.denial(Role.FREIGHT_FORWARDER, false, "POST", "/api/shipments"))
				.contains(ShipmentCreationForbiddenException.MESSAGE);
	}

	@Test
	void doesNotDecideArchiveOrPublicPaths() {
		assertThat(RolePermissionMatrix.denial(Role.LOGISTICS_MANAGER, true, "PATCH", "/api/shipments/8/status")).isEmpty();
		assertThat(RolePermissionMatrix.denial(Role.IMPORTER, false, "GET", "/api/session")).isEmpty();
		assertThat(RolePermissionMatrix.denial(Role.FREIGHT_FORWARDER, true, "POST", "/api/login")).isEmpty();
	}
}
