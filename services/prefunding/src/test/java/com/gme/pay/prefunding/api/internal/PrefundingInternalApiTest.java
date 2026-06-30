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
 * MockMvc test for the cross-service {@link PrefundingInternalController} consumed by
 * transaction-mgmt. Verifies: atomic deduct returns balance + ledgerEntryId; idempotent replay
 * (body key AND header key) does not double-charge; insufficient funds → 402; missing key → 400;
 * reverse restores the balance and reports the credit entry id; reverse is idempotent.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrefundingInternalApiTest {

    private static final String PARTNER = "INT_P1";

    @Autowired private MockMvc mvc;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private BalanceAlertRepository alerts;

    @BeforeEach
    void seed() {
        alerts.deleteAll();
        ledger.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("1000.00000000"), new BigDecimal("100.00000000"), Instant.now()));
    }

    @Test
    @DisplayName("POST /deduct: atomic deduction returns balance + ledgerEntryId; replay is a no-op")
    void deduct_returnsBalanceAndLedgerId_andIsIdempotent() throws Exception {
        String body = "{\"idempotencyKey\":\"TXN-1\",\"amountUsd\":\"250.00\"}";

        String response = mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.balance").value(750.0))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.ledgerEntryId").isNumber())
                .andReturn().getResponse().getContentAsString();

        // Replay with the SAME key must not debit again: balance unchanged, replayed=true, same entry id.
        Long firstId = idFrom(response);
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(750.0))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.ledgerEntryId").value(firstId.intValue()));

        // Exactly one DEBIT ledger row exists.
        assertEquals(1L, ledger.countByPartnerId(PARTNER));
        assertEquals(0, balances.findById(PARTNER).orElseThrow().getBalance()
                .compareTo(new BigDecimal("750.00000000")));
    }

    @Test
    @DisplayName("POST /deduct: Idempotency-Key header is honoured when body key is absent")
    void deduct_honoursHeaderIdempotencyKey() throws Exception {
        String body = "{\"amountUsd\":\"100.00\"}";
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .header("Idempotency-Key", "HDR-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(900.0));
        // Same header key replays.
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .header("Idempotency-Key", "HDR-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1L, ledger.countByPartnerId(PARTNER));
    }

    @Test
    @DisplayName("POST /deduct: insufficient funds → 402 INSUFFICIENT_PREFUNDING, nothing written")
    void deduct_insufficientFunds_402() throws Exception {
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"TXN-BIG\",\"amountUsd\":\"5000.00\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_PREFUNDING"));
        assertEquals(0L, ledger.countByPartnerId(PARTNER));
    }

    @Test
    @DisplayName("POST /deduct: missing idempotency key (body+header) → 400")
    void deduct_missingKey_400() throws Exception {
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountUsd\":\"100.00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /reverse: restores balance, reports credit entry id; second reverse is 0 no-op")
    void reverse_restoresBalance_andIsIdempotent() throws Exception {
        mvc.perform(post("/internal/v1/prefunding/{p}/deduct", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"TXN-R\",\"amountUsd\":\"250.00\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/internal/v1/prefunding/{p}/reverse", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txnRef\":\"TXN-R\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversedUsd").value(250.0))
                .andExpect(jsonPath("$.balance").value(1000.0))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.ledgerEntryId").isNumber());

        // Second reverse: idempotent no-op (0 reversed, balance unchanged).
        mvc.perform(post("/internal/v1/prefunding/{p}/reverse", PARTNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txnRef\":\"TXN-R\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversedUsd").value(0));

        assertEquals(0, balances.findById(PARTNER).orElseThrow().getBalance()
                .compareTo(new BigDecimal("1000.00000000")));
    }

    private static Long idFrom(String json) {
        int i = json.indexOf("\"ledgerEntryId\":");
        int start = i + "\"ledgerEntryId\":".length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}
