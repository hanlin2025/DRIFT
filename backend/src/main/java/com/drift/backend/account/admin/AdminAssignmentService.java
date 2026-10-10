package com.drift.backend.account.admin;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.admin.exception.AdminAssignmentConflictException;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.admin.exception.AssignedUserNotFoundException;
import com.drift.backend.account.admin.exception.InvalidAssignmentException;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

@Service
public class AdminAssignmentService {

	private static final String ONE_ADMIN = "A company can have only one admin";

	private final UserAccountRepository users;
	private final AccountRoleRepository roles;
	private final CompanyRepository companies;

	public AdminAssignmentService(UserAccountRepository users, AccountRoleRepository roles, CompanyRepository companies) {
		this.users = users;
		this.roles = roles;
		this.companies = companies;
	}

	@Transactional(readOnly = true)
	public List<AdminUserResponse> users(AuthenticatedUser principal) {
		requireAdmin(principal);
		return users.findAllForAssignment().stream().map(AdminUserResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public List<AdminRoleResponse> roles(AuthenticatedUser principal) {
		requireAdmin(principal);
		return roles.findAll().stream()
				.map(role -> new AdminRoleResponse(role.getCode(), label(role.getCode())))
				.sorted(Comparator.comparing(AdminRoleResponse::label))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<AdminOrganisationResponse> organisations(AuthenticatedUser principal) {
		requireAdmin(principal);
		return companies.findByActiveTrueOrderByNameAscIdAsc().stream()
				.map(AdminOrganisationResponse::from)
				.toList();
	}

	@Transactional
	public AssignmentResponse assign(AuthenticatedUser principal, Long userId, AssignUserRequest request) {
		requireAdmin(principal);
		if (request == null) {
			throw new InvalidAssignmentException();
		}
		UserAccount target = users.findById(userId).orElseThrow(AssignedUserNotFoundException::new);
		target.assign(role(request.role()), organisation(request.organisationId()));
		try {
			users.saveAndFlush(target);
		} catch (RuntimeException ex) {
			if (mentions(ex, ONE_ADMIN)) {
				throw new AdminAssignmentConflictException();
			}
			throw ex;
		}
		return AssignmentResponse.from(target);
	}

	private void requireAdmin(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		if (account.getRole() != Role.ADMIN) {
			throw new AssignmentForbiddenException();
		}
	}

	private AccountRole role(String code) {
		if (code == null || code.isBlank()) {
			throw new InvalidAssignmentException();
		}
		Role parsed;
		try {
			parsed = Role.valueOf(code);
		} catch (IllegalArgumentException ex) {
			throw new InvalidAssignmentException();
		}
		return roles.findByCode(parsed).orElseThrow(InvalidAssignmentException::new);
	}

	private Company organisation(Long organisationId) {
		if (organisationId == null) {
			throw new InvalidAssignmentException();
		}
		return companies.findById(organisationId).filter(Company::isActive).orElseThrow(InvalidAssignmentException::new);
	}

	static String label(Role role) {
		return switch (role) {
			case ADMIN -> "Administrator";
			case FREIGHT_FORWARDER -> "Freight forwarder";
			case IMPORTER -> "Importer";
			case LOGISTICS_MANAGER -> "Logistics manager";
		};
	}

	private static boolean mentions(Throwable failure, String text) {
		Throwable current = failure;
		while (current != null) {
			if (current.getMessage() != null && current.getMessage().contains(text)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
