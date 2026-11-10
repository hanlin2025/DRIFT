package com.drift.backend.account.admin;

import org.springframework.stereotype.Component;

import com.drift.backend.account.AccountStatus;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.admin.exception.LastAdministratorException;
import com.drift.backend.company.Company;

/**
 * Keeps every company with at least one active administrator. Call it before an assignment or removal
 * is applied. Under the one-admin trigger (V13) the self-assignment and self-removal checks normally
 * fire first; this guard still holds if that trigger is relaxed.
 */
@Component
public class AdministratorContinuity {

	private final UserAccountRepository users;

	public AdministratorContinuity(UserAccountRepository users) {
		this.users = users;
	}

	public void beforeAssignment(UserAccount target, Role nextRole, Company nextCompany) {
		Company current = target.getCompany();
		boolean staysAdministrator = nextRole == Role.ADMIN && current != null && nextCompany != null
				&& current.getId().equals(nextCompany.getId());
		if (!staysAdministrator) {
			requireAnotherAdministrator(target);
		}
	}

	public void beforeRemoval(UserAccount target) {
		requireAnotherAdministrator(target);
	}

	private void requireAnotherAdministrator(UserAccount target) {
		if (target.getRole() != Role.ADMIN || target.getStatus() != AccountStatus.ACTIVE || target.getCompany() == null) {
			return;
		}
		long others = users.countOtherActiveAdministrators(target.getCompany().getId(), target.getId());
		if (others == 0) {
			throw new LastAdministratorException();
		}
	}
}
