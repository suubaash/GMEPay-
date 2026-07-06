package com.gme.pay.auth.web;

import com.gme.pay.auth.domain.JwtHelper;
import com.gme.pay.auth.domain.SecretHasher;
import com.gme.pay.auth.persistence.PrincipalEntity;
import com.gme.pay.auth.persistence.PrincipalRepository;
import com.gme.pay.auth.persistence.RoleEntity;
import com.gme.pay.auth.service.HumanLoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc test for {@link HumanLoginController} wired to a REAL
 * {@link HumanLoginService} + {@link JwtHelper} over a mocked
 * {@link PrincipalRepository} — so the happy path exercises genuine PBKDF2
 * verification and mints (and verifies) a genuine 3-part HS256 JWT, not a mock.
 *
 * <p>Stored credentials use a low iteration count (per-row {@code
 * password_iterations}, exactly how a future upgrade would coexist with old
 * rows) to keep the suite fast.
 */
class HumanLoginControllerTest {

    private static final String SIGNING_SECRET = "unit-test-signing-secret-32-chars!!";
    private static final long TTL_SECONDS = 900;
    private static final int TEST_ITERATIONS = 1_000;
    private static final String GOOD_PASSWORD = "gmepay-dev-admin";

    private PrincipalRepository principals;
    private JwtHelper jwtHelper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        principals = mock(PrincipalRepository.class);
        when(principals.save(any())).thenAnswer(inv -> inv.getArgument(0));
        jwtHelper = new JwtHelper(SIGNING_SECRET, TTL_SECONDS);
        HumanLoginService service = new HumanLoginService(principals, jwtHelper, TTL_SECONDS);
        mvc = standaloneSetup(new HumanLoginController(service)).build();
    }

    private static PrincipalEntity operator(String username, String password,
                                            PrincipalEntity.Status status) {
        PrincipalEntity p = new PrincipalEntity(
                PrincipalEntity.Type.OPERATOR, username, "Test Operator", null, Instant.now());
        String salt = SecretHasher.newSaltHex();
        p.setPasswordCredential(
                SecretHasher.hashHex(password, salt, TEST_ITERATIONS), salt, TEST_ITERATIONS);
        p.setStatus(status);
        p.addRole(new RoleEntity("HUB_ADMIN", "admin", Instant.now()));
        return p;
    }

    @Test
    @DisplayName("POST /v1/auth/login returns a real 3-part HS256 JWT with preferred_username + roles")
    void login_success_mintsRealJwt() throws Exception {
        when(principals.findByUsername("admin"))
                .thenReturn(Optional.of(operator("admin", GOOD_PASSWORD, PrincipalEntity.Status.ACTIVE)));

        MvcResult result = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + GOOD_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.roles[0]").value("HUB_ADMIN"))
                .andExpect(jsonPath("$.expiresAt").isNumber())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String token = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("token").asText();

        // Real compact JWT: exactly three dot-separated segments, not "mock.eyJ…".
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(token).doesNotStartWith("mock.");

        // Signature verifies against the issuing key and sub round-trips.
        JwtHelper.JwtClaims claims = jwtHelper.verify(token);
        assertThat(claims).isNotNull();
        assertThat(claims.subject()).isEqualTo("admin");

        // Decoded payload carries the SPA-facing claims.
        String payloadJson = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("\"preferred_username\":\"admin\"");
        assertThat(payloadJson).contains("\"roles\":[\"HUB_ADMIN\"]");
    }

    @Test
    @DisplayName("POST /v1/auth/login returns 401 for a wrong password")
    void login_wrongPassword_returns401() throws Exception {
        when(principals.findByUsername("admin"))
                .thenReturn(Optional.of(operator("admin", GOOD_PASSWORD, PrincipalEntity.Status.ACTIVE)));

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/login returns 401 for an unknown user")
    void login_unknownUser_returns401() throws Exception {
        when(principals.findByUsername("ghost")).thenReturn(Optional.empty());

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"whatever-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/login returns 401 for a non-ACTIVE principal even with the right password")
    void login_inactivePrincipal_returns401() throws Exception {
        when(principals.findByUsername("admin"))
                .thenReturn(Optional.of(operator("admin", GOOD_PASSWORD, PrincipalEntity.Status.DISABLED)));

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + GOOD_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/login returns 401 for blank credentials")
    void login_blankCredentials_returns401() throws Exception {
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isUnauthorized());
    }
}
