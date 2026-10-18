package com.drift.backend.account.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountStatus;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.admin.exception.AccountAlreadyRemovedException;
import com.drift.backend.account.admin.exception.AssignedUserNotFoundException;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;

@Service
public class AccountRemovalService {

	private final UserAccountRepository users;

	public AccountRemovalService(UserAccountRepository users) {
		this.users = users;
	}

	@Transactional
	public AdminUserResponse remove(AuthenticatedUser principal, Long userId) {
		requireAdmin(principal);
		UserAccount target = users.findById(userId).orElseThrow(AssignedUserNotFoundException::new);
		if (target.getStatus() == AccountStatus.REMOVED) {
			throw new AccountAlreadyRemovedException();
		}
		target.remove();
		users.saveAndFlush(target);
		return AdminUserResponse.from(target);
	}

	private void requireAdmin(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		if (account.getRole() != Role.ADMIN) {
			throw new AssignmentForbiddenException();
		}
	}
}
