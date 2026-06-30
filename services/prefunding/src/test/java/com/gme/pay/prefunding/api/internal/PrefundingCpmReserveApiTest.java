package com.gme.pay.prefunding.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc tests for the Phase-2 CPM reserve/release surface on {@link PrefundingInternalController}
 * (qr-service IR-qr-3). Verifies: reserve holds against available and returns a reservationId; reserve
 * is idempotent on idempotencyKey; release restores available; release is idempotent; a reserve that
 * would overdraw available (balance + credit_limit − reserved) is 402; reserve then deduct against the
 * remaining balance never goes negative.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrefundingCpmReserveApiTest {

    private static final String PARTNER = "CPM_P1";

    @Autowired private MockMvc mvc;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private BalanceAlertRepository alerts;

    @BeforeEach
    void seed() {
        alerts.deleteAll();
        ledger.deleteAll();
        balances.deleteAll();
        // 1000 USD balance, no credit headroom → available == balance.
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("1000.00000000"), new BigDecimal("100.00000000"), Instant.now()));
    }

    @Test
    @DisplayName("POST /reserve: holds against available, returns reservationId; replay is a no-op")
    void reserve_holdsAndIsIdempotent() throws Exception {
        String body = "{\"partnerId\":42,\"amountUsd\":\"300.00\",\"idempotencyKey\":\"CPM-1\",\"txnRef\":\"T-1\"}";

        String resp = mvc.perform(post("/internal/v1/prefunding/{p}/reserve", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(42))
                .andExpect(jsonPath("$.reservationId").isNotEmpty())
                .andExpect(jsonPath("$.reservedAmountUsd").value("300.00"))
                .andExpect(jsonPath("$.availableUsd").value("700.00000000"))
                .andExpect(jsonPath("$.reservedUsd").value("300.00000000"))
                .andReturn().getResponse().getContentAsString();

        String reservationId = resp.replaceAll(".*\"reservationId\":\"([^\"]+)\".*", "$1");

        // Replay same key: no new hold, same reservationId, reserved still 300.
        mvc.perform(post("/internal/v1/prefunding/{p}/reserve", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId))
                .andExpect(jsonPath("$.reservedUsd").value("300.00000000"))
                .andExpect(jsonPath("$.availableUsd").value("700.00000000"));

        assertEquals(0, balances.findById(PARTNER).orElseThrow().getReserved()
                .compareTo(new BigDecimal("300")));
    }

    @Test
    @DisplayName("POST /release: restores available; second release is a 0 no-op")
    void release_restoresAndIsIdempotent() throws Exception {
        mvc.perform(post("/internal/v1/prefunding/{p}/reserve", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":42,\"amountUsd\":\"300.00\",\"idempotencyKey\":\"CPM-R\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/internal/v1/prefunding/{p}/release", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":42,\"idempotencyKey\":\"CPM-R\",\"reason\":\"expiry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedUsd").value(300.0))
                .andExpect(jsonPath("$.reservedUsd").value(0))
                .andExpect(jsonPath("$.balance").value(1000.0));

        // Second release: idempotent 0 no-op.
        mvc.perform(post("/internal/v1/prefunding/{p}/release", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":42,\"idempotencyKey\":\"CPM-R\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedUsd").value(0));

        assertEquals(0, balances.findById(PARTNER).orElseThrow().getReserved().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("POST /reserve: a hold beyond available → 402 INSUFFICIENT_PREFUNDING, nothing held")
    void reserve_overdraw_402() throws Exception {
        mvc.perform(post("/internal/v1/prefunding/{p}/reserve", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":42,\"amountUsd\":\"5000.00\",\"idempotencyKey\":\"CPM-BIG\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_PREFUNDING"));
        assertEquals(0, balances.findById(PARTNER).orElseThrow().getReserved().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("reserve + deduct interaction: a deduct against the remaining balance never goes negative")
    void reserveThenDeduct_nonNegative() throws Exception {
        // Hold 700 of 1000; available is now 300.
        mvc.perform(post("/internal/v1/prefunding/{p}/reserve", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerId\":42,\"amountUsd\":\"700.00\",\"idempotencyKey\":\"CPM-D\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableUsd").value("300.00000000"));

        // A 300 deduct still fits available; balance → 700, reserved stays 700 → available 0.
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"DBT-1\",\"amountUsd\":\"300.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(700.0));

        // A further deduct that would push available below zero is rejected; nothing moves.
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"DBT-2\",\"amountUsd\":\"100.00\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_PREFUNDING"));

        PartnerBalanceEntity row = balances.findById(PARTNER).orElseThrow();
        assertEquals(0, row.getBalance().compareTo(new BigDecimal("700")));
        assertEquals(0, row.getReserved().compareTo(new BigDecimal("700")));
    }
}
