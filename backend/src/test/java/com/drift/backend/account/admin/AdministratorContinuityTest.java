package com.drift.backend.account.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.admin.exception.LastAdministratorException;
import com.drift.backend.company.Company;

class AdministratorContinuityTest {

	private UserAccountRepository users;
	private AdministratorContinuity continuity;
	private Company harbourline;
	private Company straits;

	@BeforeEach
	void setUp() {
		users = mock(UserAccountRepository.class);
		continuity = new AdministratorContinuity(users);
		harbourline = company(1L, "HARBOURLINE");
		straits = company(2L, "STRAITS");
	}

	@Test
	void rejectsDemotingTheOnlyActiveAdministrator() {
		UserAccount admin = account(10L, Role.ADMIN, harbourline);
		when(users.countOtherActiveAdministrators(1L, 10L)).thenReturn(0L);

		assertThatThrownBy(() -> continuity.beforeAssignment(admin, Role.IMPORTER, harbourline))
				.isInstanceOf(LastAdministratorException.class)
				.hasMessage(LastAdministratorException.MESSAGE);
	}

	@Test
	void rejectsMovingTheOnlyActiveAdministratorToAnotherCompany() {
		UserAccount admin = account(10L, Role.ADMIN, harbourline);
		when(users.countOtherActiveAdministrators(1L, 10L)).thenReturn(0L);

		assertThatThrownBy(() -> continuity.beforeAssignment(admin, Role.ADMIN, straits))
				.isInstanceOf(LastAdministratorException.class);
	}

	@Test
	void rejectsRemovingTheOnlyActiveAdministrator() {
		UserAccount admin = account(10L, Role.ADMIN, harbourline);
		when(users.countOtherActiveAdministrators(1L, 10L)).thenReturn(0L);

		assertThatThrownBy(() -> continuity.beforeRemoval(admin)).isInstanceOf(LastAdministratorException.class);
	}

	@Test
	void allowsDemotingMovingOrRemovingAnAdministratorWhenAnotherActiveAdministratorRemains() {
		UserAccount admin = account(10L, Role.ADMIN, harbourline);
		when(users.countOtherActiveAdministrators(1L, 10L)).thenReturn(1L);

		assertThatCode(() -> continuity.beforeAssignment(admin, Role.IMPORTER, harbourline)).doesNotThrowAnyException();
		assertThatCode(() -> continuity.beforeAssignment(admin, Role.ADMIN, straits)).doesNotThrowAnyException();
		assertThatCode(() -> continuity.beforeRemoval(admin)).doesNotThrowAnyException();
	}

	@Test
	void allowsKeepingTheOnlyAdministratorAsAdministratorOfTheSameCompany() {
		UserAccount admin = account(10L, Role.ADMIN, harbourline);

		assertThatCode(() -> continuity.beforeAssignment(admin, Role.ADMIN, harbourline)).doesNotThrowAnyException();
		verify(users, never()).countOtherActiveAdministrators(anyLong(), anyLong());
	}

	@Test
	void ignoresChangesToNonAdministratorsAndToRemovedAdministrators() {
		UserAccount importer = account(11L, Role.IMPORTER, harbourline);
		UserAccount removedAdmin = account(12L, Role.ADMIN, harbourline);
		removedAdmin.remove();

		assertThatCode(() -> continuity.beforeAssignment(importer, Role.FREIGHT_FORWARDER, harbourline))
				.doesNotThrowAnyException();
		assertThatCode(() -> continuity.beforeRemoval(importer)).doesNotThrowAnyException();
		assertThatCode(() -> continuity.beforeAssignment(removedAdmin, Role.IMPORTER, harbourline))
				.doesNotThrowAnyException();
		verify(users, never()).countOtherActiveAdministrators(anyLong(), anyLong());
	}

	private static Company company(Long id, String code) {
		Company company = Company.createActive(code, code + " Ltd", null);
		ReflectionTestUtils.setField(company, "id", id);
		return company;
	}

	private static UserAccount account(Long id, Role role, Company company) {
		UserAccount account = new UserAccount("User " + id, "user" + id + "@example.com", "hash", new AccountRole(role), company);
		ReflectionTestUtils.setField(account, "id", id);
		return account;
	}
}
