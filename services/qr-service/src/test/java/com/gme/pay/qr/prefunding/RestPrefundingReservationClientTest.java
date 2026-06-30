package com.gme.pay.qr.prefunding;

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

/** Verifies the gated REST client maps prefunding reserve/release to the domain port (IR-qr-3). */
class RestPrefundingReservationClientTest {

    private MockRestServiceServer server;
    private RestPrefundingReservationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPrefundingReservationClient(builder, "http://prefunding:8080", "tok");
    }

    @Test
    void reserveReturnsHandleAndAmount() {
        server.expect(requestTo("http://prefunding:8080/internal/v1/prefunding/7/reserve"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "tok"))
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
}
