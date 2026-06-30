package com.gme.pay.prefunding.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerRepository;
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
 * MockMvc test for the config-registry → prefunding credit-limit / AML-cap push (IR-pf-2):
 * {@code PUT /internal/v1/prefunding/{partnerId}/credit-limit}. Verifies the limits are stored,
 * a re-PUT updates them (idempotent upsert), the stored credit limit takes effect on the deduct
 * gate (available = balance + credit_limit − reserved), and a stored AML daily cap is enforced on
 * {@code /cumulative-charge} without a per-request cap.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrefundingCreditLimitApiTest {

    private static final String PARTNER = "CL_P1";

    @Autowired private MockMvc mvc;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private BalanceAlertRepository alerts;
    @Autowired private CumulativeUsageLedgerRepository cumulative;

    @BeforeEach
    void seed() {
        alerts.deleteAll();
        ledger.deleteAll();
        cumulative.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("100.00000000"), new BigDecimal("10.00000000"), Instant.now()));
    }

    @Test
    @DisplayName("PUT credit-limit: stores credit limit + AML caps; re-PUT updates; stored values readable")
    void put_storesAndUpdates_andIsReadable() throws Exception {
        String body = "{\"creditLimitUsd\":\"500.00\",\"amlDailyCapUsd\":\"1000.00\","
                + "\"amlMonthlyCapUsd\":\"5000.00\",\"amlAnnualCapUsd\":\"50000.00\","
                + "\"amlDailyTxnCountCap\":25}";
        mvc.perform(put("/internal/v1/prefunding/{p}/credit-limit", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.creditLimitUsd").value(500.0))
                .andExpect(jsonPath("$.amlDailyCapUsd").value(1000.0))
                .andExpect(jsonPath("$.amlDailyTxnCountCap").value(25))
                // available = balance(100) + creditLimit(500) - reserved(0)
                .andExpect(jsonPath("$.available").value(600.0));

        PartnerBalanceEntity row = balances.findById(PARTNER).orElseThrow();
        assertEquals(0, row.getCreditLimit().compareTo(new BigDecimal("500.00")));
        assertEquals(0, row.getAmlDailyCapUsd().compareTo(new BigDecimal("1000.00")));
        assertEquals(25, row.getAmlDailyTxnCountCap());

        // Re-PUT with new values overwrites; a null cap clears it.
        String body2 = "{\"creditLimitUsd\":\"250.00\",\"amlDailyCapUsd\":\"2000.00\"}";
        mvc.perform(put("/internal/v1/prefunding/{p}/credit-limit", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimitUsd").value(250.0))
                .andExpect(jsonPath("$.amlDailyCapUsd").value(2000.0))
                .andExpect(jsonPath("$.available").value(350.0));

        PartnerBalanceEntity row2 = balances.findById(PARTNER).orElseThrow();
        assertEquals(0, row2.getCreditLimit().compareTo(new BigDecimal("250.00")));
        assertEquals(0, row2.getAmlDailyCapUsd().compareTo(new BigDecimal("2000.00")));
        assertNull(row2.getAmlMonthlyCapUsd());      // cleared on re-PUT
        assertNull(row2.getAmlDailyTxnCountCap());
    }

    @Test
    @DisplayName("PUT credit-limit creates the row when the partner has no balance yet (push before provision)")
    void put_upsertsMissingPartner() throws Exception {
        balances.deleteAll();
        mvc.perform(put("/internal/v1/prefunding/{p}/credit-limit", "NEW_P")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimitUsd\":\"300.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimitUsd").value(300.0))
                .andExpect(jsonPath("$.balance").value(0.0))
                .andExpect(jsonPath("$.available").value(300.0));
        assertEquals(0, balances.findById("NEW_P").orElseThrow().getCreditLimit()
                .compareTo(new BigDecimal("300.00")));
    }

    @Test
    @DisplayName("Stored credit limit takes effect: a deduct beyond balance but within credit limit succeeds; beyond limit → 402")
    void storedCreditLimit_appliesToDeductGate() throws Exception {
        // balance 100, credit limit 50 → available 150
        mvc.perform(put("/internal/v1/prefunding/{p}/credit-limit", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimitUsd\":\"50.00\"}"))
                .andExpect(status().isOk());

        // 140 <= 150 available: succeeds (would have failed under the old balance-only rule of 100).
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"D-OK\",\"amountUsd\":\"140.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(-40.0));

        // A further 20 would need available 60 but only 10 remains → 402.
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"D-OVER\",\"amountUsd\":\"20.00\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_PREFUNDING"));
    }

    @Test
    @DisplayName("Stored AML daily cap is enforced on cumulative-charge without a per-request cap")
    void storedAmlCap_appliesToCumulativeChargeGate() throws Exception {
        mvc.perform(put("/internal/v1/prefunding/{p}/credit-limit", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimitUsd\":\"0.00\",\"amlDailyCapUsd\":\"100.00\"}"))
                .andExpect(status().isOk());

        // 80 <= 100 stored daily cap: passes (no per-request cap supplied).
        mvc.perform(post("/v1/prefunding/{p}/cumulative-charge", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txnRef\":\"C-1\",\"amountUsd\":\"80.00\"}"))
                .andExpect(status().isOk());

        // +30 → 110 > 100 stored daily cap → 422 CUMULATIVE_LIMIT_EXCEEDED.
        mvc.perform(post("/v1/prefunding/{p}/cumulative-charge", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txnRef\":\"C-2\",\"amountUsd\":\"30.00\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUMULATIVE_LIMIT_EXCEEDED"));
    }
}
