package com.drift.backend.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.registration.invitation.InvitationToken;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegistrationIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired jakarta.persistence.EntityManager entityManager;
    private String email;
    private String token;
    private Long companyId;

    @BeforeEach
    void invite() {
        email = "cdg17-" + UUID.randomUUID() + "@example.com";
        token = UUID.randomUUID().toString().replace("-", "") + "abcdefghijk";
        companyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
        jdbc.update("INSERT INTO invitations (email, company_id, role, token_hash, expires_at) VALUES (?, ?, 'FREIGHT_FORWARDER', ?, ?)",
                email, companyId, InvitationToken.hash(token), java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    private String request(String name, String address, String password, String invitation) {
        return """
                {"fullName":"%s","email":"%s","password":"%s","invitationToken":"%s"}
                """.formatted(name, address, password, invitation);
    }

    private int register(String body) throws Exception {
        return mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void resolvesOnlyTheInvitedCompanyAndRole() throws Exception {
        mvc.perform(post("/api/invitations/resolve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.companyName").value("Harbourline Logistics (Demo)"))
                .andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
                .andExpect(jsonPath("$.tokenHash").doesNotExist());
    }

    @Test
    void registersNormalizesHashesAndConsumes() throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(request("  Alice Tan  ", email.toUpperCase(), "Example123", token)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fullName").value("Alice Tan"))
                .andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.passwordHash").doesNotExist());
        entityManager.flush();
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).isNotEqualTo("Example123");
        assertThat(passwords.matches("Example123", hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT company_id FROM users WHERE email = ?", Long.class, email)).isEqualTo(companyId);
        assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE email = ?", String.class, email)).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM invitations WHERE email = ?", Boolean.class, email)).isTrue();
    }

    @Test
    void clientCannotOverrideCompanyOrRole() throws Exception {
        String body = request("Alice", email, "Example123", token).strip().replace("}", ",\"role\":\"IMPORTER\",\"companyId\":999999}");
        assertThat(register(body)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email)).isEqualTo("FREIGHT_FORWARDER");
        assertThat(jdbc.queryForObject("SELECT company_id FROM users WHERE email = ?", Long.class, email)).isEqualTo(companyId);
    }

    @Test
    void rejectsMissingFieldsWithoutConsumingInvitation() throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.fullName").exists())
                .andExpect(jsonPath("$.errors.email").exists()).andExpect(jsonPath("$.errors.password").exists());
        assertPending();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "lowercase123", "UPPERCASE123", "NoDigitsHere", "Has space1", "NoSpace\u00a01"})
    void rejectsInvalidPasswords(String password) throws Exception {
        assertThat(register(request("Alice", email, password, token))).isEqualTo(400);
        assertPending();
    }

    @Test
    void rejectsPasswordBeyondBcryptByteLimit() throws Exception {
        assertThat(register(request("Alice", email, "Ab1" + "\u00e9".repeat(35), token))).isEqualTo(400);
        assertPending();
    }

    @Test
    void rejectsWrongEmailAndUnknownToken() throws Exception {
        assertThat(register(request("Alice", "other@example.com", "Example123", token))).isEqualTo(400);
        assertThat(register(request("Alice", email, "Example123", "z".repeat(43)))).isEqualTo(400);
        assertPending();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "revoked", "consumed", "inactive-company"})
    void rejectsIneligibleInvitations(String state) throws Exception {
        switch (state) {
            case "expired" -> jdbc.update("UPDATE invitations SET expires_at = NOW() - INTERVAL '1 hour' WHERE email = ?", email);
            case "revoked" -> jdbc.update("UPDATE invitations SET status = 'REVOKED' WHERE email = ?", email);
            case "consumed" -> jdbc.update("UPDATE invitations SET status = 'CONSUMED', consumed_at = NOW() WHERE email = ?", email);
            case "inactive-company" -> jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", companyId);
            default -> throw new IllegalArgumentException(state);
        }
        assertThat(register(request("Alice", email, "Example123", token))).isEqualTo(400);
        mvc.perform(post("/api/invitations/resolve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test
    void duplicateEmailDoesNotConsumeInvitation() throws Exception {
        jdbc.update("INSERT INTO users (full_name, email, password_hash, role) VALUES ('Existing', ?, 'existing-hash', 'IMPORTER')", email.toUpperCase());
        assertThat(register(request("Alice", email, "Example123", token))).isEqualTo(409);
        assertPending();
    }

    @Test
    void malformedJsonReturnsReadableError() throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentClaimsCreateExactlyOneAccount() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return register(request("Alice", email, "Example123", token)); });
            var second = executor.submit(() -> { start.await(); return register(request("Alice", email, "Example123", token)); });
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 400);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM invitations WHERE email = ?", email);
            jdbc.update("DELETE FROM users WHERE email = ?", email);
        }
    }

    private void assertPending() {
        assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE email = ?", String.class, email)).isEqualTo("PENDING");
    }
}
