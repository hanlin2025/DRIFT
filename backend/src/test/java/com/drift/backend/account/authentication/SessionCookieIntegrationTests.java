package com.drift.backend.account.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.CompanyRepository;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SessionCookieIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired UserAccountRepository users;
	@Autowired AccountRoleRepository roles;
	@Autowired CompanyRepository companies;
	@Autowired PasswordEncoder passwords;

	@Test
	void cookieSessionSurvivesAcrossCallsUntilSignOutAndCannotBeReplayed() throws Exception {
		String email = "cdg311-" + UUID.randomUUID() + "@example.com";
		users.saveAndFlush(new UserAccount("Casey Guest", email, passwords.encode("Example123"),
				roles.findByCode(Role.LOGISTICS_MANAGER).orElseThrow(),
				companies.findByCode("HARBOURLINE_DEMO").orElseThrow()));

		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Example123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("LOGISTICS_MANAGER"))
				.andExpect(jsonPath("$.company.code").value("HARBOURLINE_DEMO"))
				.andExpect(header().exists(HttpHeaders.SET_COOKIE))
				.andReturn();
		String token = login.getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];
		String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).contains("DRIFT_SESSION=").contains("HttpOnly").contains("SameSite=Lax").doesNotContain("Secure");
		assertThat(setCookie).doesNotContain("Domain=");
		Instant expiresAt = Instant.parse(login.getResponse().getContentAsString().split("\"expiresAt\":\"")[1].split("\"")[0]);
		assertThat(expiresAt).isAfter(Instant.now().plusSeconds(7 * 60 * 60));
		assertThat(expiresAt).isBefore(Instant.now().plusSeconds(9 * 60 * 60));

		Cookie cookie = new Cookie(SessionCookie.NAME, token);
		mvc.perform(get("/api/session").cookie(cookie))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("LOGISTICS_MANAGER"))
				.andExpect(jsonPath("$.company.name").value("Harbourline Logistics (Demo)"))
				.andExpect(jsonPath("$.token").doesNotExist());

		mvc.perform(post("/api/logout").cookie(cookie))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/session").cookie(cookie)).andExpect(status().isOk());

		mvc.perform(post("/api/logout").cookie(cookie).header(CookieCsrfFilter.HEADER, "1"))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
						org.hamcrest.Matchers.containsString("DRIFT_SESSION="),
						org.hamcrest.Matchers.containsString("Max-Age=0"),
						org.hamcrest.Matchers.containsString("HttpOnly"))));

		mvc.perform(get("/api/session").cookie(cookie)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}
}
