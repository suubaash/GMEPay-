package com.gme.pay.bff.web;

import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * Standalone MockMvc test for the real-auth {@link AuthController}: the login
 * endpoint must PROXY to auth-identity's {@code POST /v1/auth/login} (asserted
 * with {@link MockRestServiceServer} bound to the controller's RestClient, the
 * same convention as the {@code client/rest} tests) and return auth-identity's
 * body verbatim. No demo password, no locally minted {@code mock.eyJ…} token:
 * upstream 401 → 401, upstream unreachable → 503.
 */
class AuthControllerTest {

    /** Shape auth-identity's HumanLoginController returns — a REAL 3-part JWT. */
    private static final String UPSTREAM_OK_JSON = """
            {"token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhZG1pbiJ9.c2ln",
             "expiresAt":1750000000,"tokenType":"Bearer",
             "username":"admin","roles":["HUB_ADMIN"]}
            """;

    private MockRestServiceServer server;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-identity-test");
        server = MockRestServiceServer.bindTo(builder).build();
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(builder.build())).build();
    }

    @Test
    @DisplayName("POST /v1/auth/login proxies to auth-identity and returns its real JWT verbatim")
    void login_proxiesAndReturnsUpstreamBodyVerbatim() throws Exception {
        server.expect(requestTo("http://auth-identity-test/v1/auth/login"))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.password").value("gmepay-dev-admin"))
                .andRespond(withSuccess(UPSTREAM_OK_JSON, MediaType.APPLICATION_JSON));

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"gmepay-dev-admin\"}"))
                .andExpect(status().isOk())
                // Verbatim pass-through of auth-identity's response fields.
                .andExpect(MockMvcResultMatchers.jsonPath("$.token", Matchers.startsWith("eyJ")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.token", Matchers.not(Matchers.startsWith("mock."))))
                .andExpect(MockMvcResultMatchers.jsonPath("$.tokenType").value("Bearer"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.username").value("admin"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.roles[0]").value("HUB_ADMIN"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.expiresAt").value(1750000000));

        server.verify();
    }

    @Test
    @DisplayName("The old demo password is no longer accepted locally — auth-identity's 401 wins")
    void login_demoPasswordRejectedByUpstream_returns401() throws Exception {
        server.expect(requestTo("http://auth-identity-test/v1/auth/login"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.password").value("demo"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"demo\"}"))
                .andExpect(status().isUnauthorized());

        server.verify();
    }

    @Test
    @DisplayName("auth-identity unreachable → 503, never a fabricated token")
    void login_upstreamUnreachable_returns503() throws Exception {
        server.expect(requestTo("http://auth-identity-test/v1/auth/login"))
                .andExpect(method(POST))
                .andRespond(request -> {
                    throw new IOException("connection refused (simulated)");
                });

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"gmepay-dev-admin\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("auth-identity 5xx → 502 Bad Gateway (proxy does not invent a token)")
    void login_upstream5xx_returns502() throws Exception {
        server.expect(requestTo("http://auth-identity-test/v1/auth/login"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"gmepay-dev-admin\"}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("Blank credentials are rejected locally with 401 — no upstream call")
    void login_blankCredentials_returns401WithoutProxying() throws Exception {
        // No server.expect(...) — any outbound call would fail the test.
        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isUnauthorized());

        server.verify();
    }
}
