package com.gme.pay.registry.client.rest;

import com.gme.pay.registry.client.AuthIdentityClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire-contract test for {@link RestAuthIdentityClient} — the config-registry
 * SIDE of the credential-issuance contract that gap T1-1 was never exercising
 * (the stub shadowed this client in every environment).
 *
 * <h2>Why the JSON is asserted field by field</h2>
 *
 * <p>{@code RestAuthIdentityClient.IssueKeyRequest} deliberately MIRRORS
 * auth-identity's {@code com.gme.pay.auth.dto.IssueKeyRequest} instead of
 * importing it (MSA rule 5: a service's internal DTOs stay private). Two records
 * that must stay identical with no compiler linking them is exactly the drift
 * risk that produces credentials nobody can verify — a renamed field would
 * simply arrive as {@code null} and auth-identity would 400, or worse, issue
 * against a null {@code partnerId}. So:
 *
 * <ul>
 *   <li>the request assertions below pin all seven wire field NAMES;</li>
 *   <li>the happy-path response body is auth-identity's REAL six-field
 *       {@code IssueKeyResponse} shape ({@code keyId}, {@code secretPlaintext},
 *       {@code prefix}, {@code environment}, {@code createdAt},
 *       {@code expiresAt}) — proving the client's three-field mirror tolerates
 *       the extra fields via {@code @JsonIgnoreProperties(ignoreUnknown)} rather
 *       than blowing up on them.</li>
 * </ul>
 *
 * <p>The matching auth-identity-side proof (issue → resolve → revoke over the
 * real controller + DB) lives in
 * {@code services/auth-identity/.../PartnerCredentialIssuanceContractTest}.
 * Keep the two in step: they are the only thing tying the mirrored records
 * together.
 */
@DisplayName("T1-1: RestAuthIdentityClient speaks auth-identity's issuance contract")
class RestAuthIdentityClientTest {

    private static final String BASE = "http://auth-identity:8080";
    private static final Instant EXPIRES = Instant.parse("2027-07-28T00:00:00Z");

    private MockRestServiceServer server;
    private RestAuthIdentityClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestAuthIdentityClient(builder.build());
    }

    private static AuthIdentityClient.IssueKeyCommand command() {
        return new AuthIdentityClient.IssueKeyCommand(
                42L, "GMEREMIT", "PRODUCTION", "API", "pk_live_", "sk_live_", EXPIRES);
    }

    @Test
    @DisplayName("issueKey: POSTs /internal/auth/keys with all 7 fields and reads the one-time secret")
    void issueKey_happyPath() {
        server.expect(requestTo(BASE + "/internal/auth/keys"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.partnerId").value(42))
                .andExpect(jsonPath("$.partnerCode").value("GMEREMIT"))
                .andExpect(jsonPath("$.environment").value("PRODUCTION"))
                .andExpect(jsonPath("$.purpose").value("API"))
                .andExpect(jsonPath("$.keyPrefix").value("pk_live_"))
                .andExpect(jsonPath("$.secretPrefix").value("sk_live_"))
                .andExpect(jsonPath("$.expiresAt").exists())
                // auth-identity's ACTUAL response record — six fields, not three.
                .andRespond(withSuccess(
                        "{\"keyId\":\"pk_live_abcdefghijkmnpqrstuvwx\","
                                + "\"secretPlaintext\":\"sk_live_0000secret\","
                                + "\"prefix\":\"pk_live_abcd\","
                                + "\"environment\":\"PRODUCTION\","
                                + "\"createdAt\":\"2026-07-28T00:00:00Z\","
                                + "\"expiresAt\":\"2027-07-28T00:00:00Z\"}",
                        MediaType.APPLICATION_JSON));

        AuthIdentityClient.IssuedKey issued = client.issueKey(command());

        assertEquals("pk_live_abcdefghijkmnpqrstuvwx", issued.keyId());
        assertEquals("sk_live_0000secret", issued.secretPlaintext());
        assertEquals(EXPIRES, issued.expiresAt());
        server.verify();
    }

    @Test
    @DisplayName("issueKey: toString() redacts the plaintext (SEC-09 §4)")
    void issuedKey_redactsSecret() {
        AuthIdentityClient.IssuedKey issued =
                new AuthIdentityClient.IssuedKey("pk_live_x", "sk_live_supersecret", EXPIRES);
        assertTrue(issued.toString().contains("REDACTED"));
        assertTrue(!issued.toString().contains("supersecret"));
    }

    @Test
    @DisplayName("issueKey: an incomplete upstream body is a 502, never a half-credential")
    void issueKey_incompleteResponseIsBadGateway() {
        server.expect(requestTo(BASE + "/internal/auth/keys"))
                .andRespond(withSuccess("{\"keyId\":\"pk_live_x\"}", MediaType.APPLICATION_JSON));

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> client.issueKey(command()));
        assertEquals(HttpStatus.BAD_GATEWAY, HttpStatus.valueOf(ex.getStatusCode().value()));
    }

    @Test
    @DisplayName("issueKey: upstream 400 surfaces verbatim (caller bug), 5xx maps to 502")
    void issueKey_failureMapping() {
        server.expect(requestTo(BASE + "/internal/auth/keys"))
                .andRespond(withBadRequest().body("purpose must be one of [API, WEBHOOK]"));
        ResponseStatusException badRequest =
                assertThrows(ResponseStatusException.class, () -> client.issueKey(command()));
        assertEquals(HttpStatus.BAD_REQUEST,
                HttpStatus.valueOf(badRequest.getStatusCode().value()));
        assertNotNull(badRequest.getReason());
        assertTrue(badRequest.getReason().contains("API, WEBHOOK"));

        setUp();
        server.expect(requestTo(BASE + "/internal/auth/keys")).andRespond(withServerError());
        ResponseStatusException serverError =
                assertThrows(ResponseStatusException.class, () -> client.issueKey(command()));
        assertEquals(HttpStatus.BAD_GATEWAY,
                HttpStatus.valueOf(serverError.getStatusCode().value()));
    }

    @Test
    @DisplayName("revokeKey: POSTs /internal/auth/keys/{keyId}/revoke and swallows 404 (idempotent)")
    void revokeKey_isIdempotent() {
        server.expect(requestTo(BASE + "/internal/auth/keys/pk_live_abc/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        client.revokeKey("pk_live_abc");
        server.verify();

        setUp();
        server.expect(requestTo(BASE + "/internal/auth/keys/pk_live_gone/revoke"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        client.revokeKey("pk_live_gone");   // must NOT throw — already gone is success
        server.verify();
    }
}
