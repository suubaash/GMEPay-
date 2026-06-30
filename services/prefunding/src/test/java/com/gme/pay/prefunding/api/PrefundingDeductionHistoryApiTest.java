package com.gme.pay.prefunding.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * MockMvc test for {@code GET /v1/prefunding/{code}/deductions?limit=N} (IR-pe-2). Verifies the
 * history is most-recent-first, bounded by {@code limit}, returns the canonical
 * {@link com.gme.pay.contracts.PrefundingDeductionHistoryView} shape, and that an unknown partner
 * yields an empty list rather than an error.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrefundingDeductionHistoryApiTest {

    private static final String PARTNER = "HIST_P1";

    @Autowired private MockMvc mvc;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private BalanceAlertRepository alerts;

    @BeforeEach
    void seed() throws Exception {
        alerts.deleteAll();
        ledger.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("1000.00000000"), new BigDecimal("100.00000000"), Instant.now()));
        // Three deductions, in order T-1, T-2, T-3.
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"idempotencyKey\":\"T-" + i + "\",\"amountUsd\":\"" + (i * 10) + ".00\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("GET /deductions: most-recent-first, capped at limit, canonical view shape")
    void deductions_recentFirst_bounded() throws Exception {
        mvc.perform(get("/v1/prefunding/{c}/deductions", PARTNER).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value(PARTNER))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.entries.length()").value(2))
                // newest first: T-3 (30) then T-2 (20)
                .andExpect(jsonPath("$.entries[0].txnRef").value("T-3"))
                .andExpect(jsonPath("$.entries[0].amountUsd").value("30.00000000"))
                .andExpect(jsonPath("$.entries[1].txnRef").value("T-2"))
                .andExpect(jsonPath("$.entries[0].at").isNotEmpty());
    }

    @Test
    @DisplayName("GET /deductions: default limit returns all, newest first")
    void deductions_defaultLimit() throws Exception {
        mvc.perform(get("/v1/prefunding/{c}/deductions", PARTNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].txnRef").value("T-3"))
                .andExpect(jsonPath("$.entries[2].txnRef").value("T-1"));
    }

    @Test
    @DisplayName("GET /deductions: unknown partner → empty list, not an error")
    void deductions_unknownPartner_empty() throws Exception {
        mvc.perform(get("/v1/prefunding/{c}/deductions", "NOPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }
}
