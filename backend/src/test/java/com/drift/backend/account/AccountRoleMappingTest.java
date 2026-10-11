package com.drift.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AccountRoleMappingTest {

	@Autowired AccountRoleRepository roles;
	@Autowired JdbcTemplate jdbc;

	@Test
	void loadsEverySeededRole() {
		assertThat(roles.findAll()).extracting(AccountRole::getCode)
				.containsExactlyInAnyOrder(Role.IMPORTER, Role.FREIGHT_FORWARDER, Role.ADMIN, Role.LOGISTICS_MANAGER);
	}

	@Test
	void anAdminMustBelongToACompanyAndBeTheOnlyAdminThere() {
		Long companyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		String first = "cdg107-admin-" + UUID.randomUUID() + "@example.com";
		String second = "cdg107-admin-" + UUID.randomUUID() + "@example.com";
		String unassigned = "cdg107-admin-" + UUID.randomUUID() + "@example.com";
		insertAdmin(first, companyId);
		assertThatThrownBy(() -> insertAdmin(second, companyId)).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertAdmin(unassigned, null)).isInstanceOf(DataAccessException.class);
		jdbc.update("DELETE FROM users WHERE email = ?", first);
	}

	private void insertAdmin(String email, Long companyId) {
		if (companyId == null) {
			jdbc.update("""
					INSERT INTO users (full_name, email, password_hash, role_id)
					SELECT 'Admin', ?, 'hash', id FROM roles WHERE code = 'ADMIN'
					""", email);
			return;
		}
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Admin', ?, 'hash', id, ? FROM roles WHERE code = 'ADMIN'
				""", email, companyId);
	}
}
