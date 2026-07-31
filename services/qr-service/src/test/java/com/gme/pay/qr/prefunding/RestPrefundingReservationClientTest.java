package com.gme.pay.qr.prefunding;

import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.qr.domain.cpm.PrefundingReservationPort.Reservation;
import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Verifies the gated REST client maps prefunding reserve/release to the domain port (IR-qr-3) and
 * that it presents the platform's service-to-service internal-auth token (T0-5 / T0-2).
 *
 * <p>Before T0-2 this client sent {@code X-Internal-Token: changeme-internal-token} — an invented
 * header nothing on the platform reads, with a default literal in main source. It is now the real
 * {@code X-Gme-Internal} token that prefunding's gate actually verifies, with no default.
 */
class RestPrefundingReservationClientTest {

    /** Test fixture, not a credential — never read outside the test source set. */
    private static final String INTERNAL_TOKEN = "fixture-token-not-a-deployment-secret";

    private MockRestServiceServer server;
    private RestPrefundingReservationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPrefundingReservationClient(
                builder, "http://prefunding:8080", INTERNAL_TOKEN);
    }

    @Test
    void reserveReturnsHandleAndAmount() {
        server.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/reserve"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(jsonPath("$.partnerId").value(7))
                .andExpect(jsonPath("$.idempotencyKey").value("CPM-1"))
                .andRespond(withSuccess("""
                        {"partnerId":7,"reservationId":"RSV-7","reservedAmountUsd":"42.00",
                         "availableUsd":"958.00","reservedUsd":"42.00"}""",
                        MediaType.APPLICATION_JSON));

        Reservation r = client.reserve(7L, new BigDecimal("42.00"), "CPM-1", "REF-1");

        assertEquals("RSV-7", r.reservationId());
        assertEquals(0, new BigDecimal("42.00").compareTo(r.reservedUsd()));
        server.verify();
    }

    @Test
    void overdraw402MapsToInsufficientPrefunding() {
        server.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/reserve"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .body("""
                                {"errorCode":"INSUFFICIENT_PREFUNDING"}""")
                        .contentType(MediaType.APPLICATION_JSON));

        QRParseException ex = assertThrows(QRParseException.class,
                () -> client.reserve(7L, new BigDecimal("9999"), "CPM-2", "REF-2"));
        assertEquals(QRErrorCode.INSUFFICIENT_PREFUNDING, ex.getErrorCode());
        server.verify();
    }

    @Test
    void releasePostsToReleaseEndpoint() {
        server.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/release"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.reservationId").value("RSV-7"))
                .andExpect(jsonPath("$.idempotencyKey").value("CPM-1"))
                .andRespond(withSuccess());

        assertDoesNotThrow(() -> client.release(7L, "RSV-7", "CPM-1", "CPM_EXPIRED"));
        server.verify();
    }

    @Test
    void releaseSwallowsServerErrorSoSweepNeverFails() {
        server.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/release"))
                .andRespond(withServerError());

        assertDoesNotThrow(() -> client.release(7L, "RSV-7", "CPM-1", "CPM_EXPIRED"));
        server.verify();
    }

    @Test
    void releaseAlsoCarriesTheInternalToken() {
        server.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/release"))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andRespond(withSuccess());

        client.release(7L, "RSV-7", "CPM-1", "CPM_EXPIRED");
        server.verify();
    }

    @Test
    void blankSecretSendsNoTokenSoAGatedPrefundingRefuses() {
        // Fail-closed, never a bypass: a missing GMEPAY_INTERNAL_AUTH_SECRET must not produce a
        // fake credential (as the retired X-Internal-Token default did) — it produces no header,
        // and the gated prefunding answers 401.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer blankServer = MockRestServiceServer.bindTo(builder).build();
        RestPrefundingReservationClient blankClient =
                new RestPrefundingReservationClient(builder, "http://prefunding:8080", "  ");

        blankServer.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/release"))
                .andExpect(headerDoesNotExist(InternalAuthHeaders.INTERNAL_TOKEN))
                .andRespond(withSuccess());

        blankClient.release(7L, "RSV-7", "CPM-1", "CPM_EXPIRED");
        blankServer.verify();
    }
}
