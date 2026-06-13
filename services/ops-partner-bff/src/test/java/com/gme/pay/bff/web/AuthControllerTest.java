package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone MockMvc test for the Phase-1 stub {@link AuthController}. Verifies
 * the happy login path (password=demo), the 401 path (empty creds), and that
 * refresh returns a new token shape.
 *
 * <p>These tests do NOT pretend to validate real auth — the controller's own
 * Javadoc flags it as Slice 1 deprecated (admin-ui swaps to Keycloak OIDC in
 * ticket 1D.3; this test + the controller are deleted in the 1C.4-cleanup follow-up).
 * The {@code "removal"} suppression keeps the build clean for the brief overlap
 * window while the old login path still serves UI traffic.
 */
@SuppressWarnings("removal")
class AuthControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthController controller = new AuthController();
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);
        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("POST /v1/auth/login returns a mock JWT when password=demo")
    void login_happyPath() throws Exception {
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", Matchers.startsWith("mock.eyJ")))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("POST /v1/auth/login returns 401 when username is empty")
    void login_emptyUsernameReturns401() throws Exception {
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"demo\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/login returns 401 when password is empty")
    void login_emptyPasswordReturns401() throws Exception {
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/login returns 401 when password is wrong")
    void login_wrongPasswordReturns401() throws Exception {
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/auth/refresh returns a fresh token")
    void refresh_returnsFreshToken() throws Exception {
        mvc.perform(post("/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"mock.eyJzdWIiOiJhbGljZSJ9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", Matchers.startsWith("mock.eyJ")))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }
}
