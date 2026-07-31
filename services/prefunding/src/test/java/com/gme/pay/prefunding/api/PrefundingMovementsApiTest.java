package com.gme.pay.prefunding.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.LedgerEntryEntity;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import com.gme.pay.prefunding.testsupport.TestInternalAuth;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code GET /v1/prefunding/{code}/movements?from=&to=} — the date-ranged float-movement query
 * (GAP T2-8).
 *
 * <p>This endpoint exists because a finance control cannot be built on {@code /deductions}: that one
 * has no date filter, clamps at 500 rows and shows DEBITs only, so a consumer had to window it
 * client-side and could be silently incomplete. Each test below pins one of the properties that
 * makes the replacement trustworthy:
 *
 * <ul>
 *   <li><b>window semantics are exact</b> — {@code from} inclusive, {@code to} exclusive, asserted at
 *       the boundary instants themselves, so consecutive days tile the timeline exactly once;</li>
 *   <li><b>reversals are visible</b> — a CREDIT appears with a positive {@code balanceDeltaUsd} and
 *       nets its DEBIT to zero, which is precisely what the old endpoint could not show;</li>
 *   <li><b>no silent cap</b> — a set of 640 entries (well past the old 500 clamp) is walked in full
 *       and every single entry is accounted for exactly once;</li>
 *   <li><b>a typo is an error, not an empty answer</b> — an unknown {@code types} value 400s.</li>
 * </ul>
 *
 * <p>Rows are inserted straight into the ledger repository rather than driven through the deduct API
 * because the boundary assertions need control of {@code created_at} to the instant.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrefundingMovementsApiTest {

    private static final String PARTNER = "MOVE_P1";
    private static final String PATH = "/v1/prefunding/{c}/movements";

    /** 2026-07-28 00:00 KST, i.e. the start of the KST business day. */
    private static final Instant DAY_START = Instant.parse("2026-07-27T15:00:00Z");
    /** 2026-07-29 00:00 KST — the EXCLUSIVE upper bound of that day. */
    private static final Instant DAY_END = Instant.parse("2026-07-28T15:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private BalanceAlertRepository alerts;
    @Autowired private ObjectMapper json;

    /** Every prefunding endpoint sits behind the internal-auth gate (T0-5). */
    private ResultActions call(MockHttpServletRequestBuilder rb) throws Exception {
        return mvc.perform(TestInternalAuth.authed(rb));
    }

    @BeforeEach
    void reset() {
        alerts.deleteAll();
        ledger.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("1000000.00000000"), new BigDecimal("100.00000000"), Instant.now()));
    }

    private void entry(String txnRef, String type, String amount, Instant at) {
        ledger.save(new LedgerEntryEntity(PARTNER, txnRef, type, new BigDecimal(amount), "USD", at));
    }

    // -----------------------------------------------------------------------------------
    // Range boundaries
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("window is half-open: from is INCLUSIVE, to is EXCLUSIVE")
    void window_fromInclusive_toExclusive() throws Exception {
        entry("BEFORE", "DEBIT", "1.00", DAY_START.minusMillis(1));
        entry("AT_FROM", "DEBIT", "2.00", DAY_START);            // included
        entry("MIDDAY", "DEBIT", "3.00", DAY_START.plusSeconds(3600));
        entry("AT_TO", "DEBIT", "4.00", DAY_END);                // excluded
        entry("AFTER", "DEBIT", "5.00", DAY_END.plusMillis(1));

        call(get(PATH, PARTNER).param("from", DAY_START.toString()).param("to", DAY_END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value(PARTNER))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.movements.length()").value(2))
                // oldest-first: a ledger range read goes forward
                .andExpect(jsonPath("$.movements[0].txnRef").value("AT_FROM"))
                .andExpect(jsonPath("$.movements[1].txnRef").value("MIDDAY"));
    }

    @Test
    @DisplayName("the entry exactly at 'to' belongs to the NEXT window — days tile, never overlap")
    void window_boundaryEntryBelongsToTheNextDay() throws Exception {
        entry("AT_TO", "DEBIT", "4.00", DAY_END);

        call(get(PATH, PARTNER).param("from", DAY_END.toString())
                        .param("to", DAY_END.plusSeconds(86400).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.movements[0].txnRef").value("AT_TO"));
    }

    @Test
    @DisplayName("an inverted or empty window is a 400, never a silently empty page")
    void window_inverted_isRejected() throws Exception {
        call(get(PATH, PARTNER).param("from", DAY_END.toString()).param("to", DAY_START.toString()))
                .andExpect(status().isBadRequest());
        // from == to: half-open ⇒ matches nothing by construction, so it is a caller error
        call(get(PATH, PARTNER).param("from", DAY_START.toString()).param("to", DAY_START.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a malformed instant is a 400 naming the field — the window is never defaulted")
    void window_malformed_isRejected() throws Exception {
        call(get(PATH, PARTNER).param("from", "2026-07-28").param("to", DAY_END.toString()))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------------------
    // Reversals / directions
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("reversals appear: a CREDIT carries a POSITIVE balanceDelta and nets its DEBIT to zero")
    void reversals_areVisibleAndNetOut() throws Exception {
        entry("TXN-1", "DEBIT", "75.00000000", DAY_START.plusSeconds(10));
        entry("TXN-1", "CREDIT", "75.00000000", DAY_START.plusSeconds(20));   // the reversal
        entry("TXN-2", "DEBIT", "40.00000000", DAY_START.plusSeconds(30));

        MvcResult res = call(get(PATH, PARTNER)
                        .param("from", DAY_START.toString()).param("to", DAY_END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.movements[0].entryType").value("DEBIT"))
                .andExpect(jsonPath("$.movements[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.movements[0].balanceDeltaUsd").value("-75.00000000"))
                .andExpect(jsonPath("$.movements[1].entryType").value("CREDIT"))
                .andExpect(jsonPath("$.movements[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.movements[1].balanceDeltaUsd").value("75.00000000"))
                .andExpect(jsonPath("$.movements[1].ledgerEntryId").isNotEmpty())
                .andReturn();

        // Summing the signed deltas for TXN-1 gives zero: the float was consumed and returned. That
        // arithmetic is impossible on /deductions, which never shows the CREDIT leg.
        JsonNode root = json.readTree(res.getResponse().getContentAsString());
        BigDecimal txn1 = BigDecimal.ZERO;
        for (JsonNode m : root.get("movements")) {
            if ("TXN-1".equals(m.get("txnRef").asText())) {
                txn1 = txn1.add(new BigDecimal(m.get("balanceDeltaUsd").asText()));
            }
        }
        org.assertj.core.api.Assertions.assertThat(txn1).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("holds and AML counters report a ZERO balance delta — they never moved the float")
    void holdsAndCounters_haveZeroDelta() throws Exception {
        entry("H-1", "RESERVE", "10.00000000", DAY_START.plusSeconds(1));
        entry("H-1", "RELEASE", "10.00000000", DAY_START.plusSeconds(2));
        entry("H-2", "CUM_CHARGE", "10.00000000", DAY_START.plusSeconds(3));
        entry("H-3", "CAPTURE", "10.00000000", DAY_START.plusSeconds(4));

        call(get(PATH, PARTNER).param("from", DAY_START.toString()).param("to", DAY_END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movements[0].direction").value("NONE"))
                .andExpect(jsonPath("$.movements[0].balanceDeltaUsd").value("0"))
                .andExpect(jsonPath("$.movements[1].direction").value("NONE"))
                .andExpect(jsonPath("$.movements[2].direction").value("NONE"))
                // a CAPTURE is a real debit — the confirm leg of the two-phase flow
                .andExpect(jsonPath("$.movements[3].direction").value("DEBIT"))
                .andExpect(jsonPath("$.movements[3].balanceDeltaUsd").value("-10.00000000"));
    }

    // -----------------------------------------------------------------------------------
    // Type filter
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("types filter narrows to the balance-moving subset")
    void typeFilter_selectsSubset() throws Exception {
        entry("T-1", "DEBIT", "1.00", DAY_START.plusSeconds(1));
        entry("T-1", "CREDIT", "1.00", DAY_START.plusSeconds(2));
        entry("T-2", "RESERVE", "1.00", DAY_START.plusSeconds(3));
        entry("T-2", "CUM_CHARGE", "1.00", DAY_START.plusSeconds(4));

        call(get(PATH, PARTNER).param("from", DAY_START.toString()).param("to", DAY_END.toString())
                        .param("types", "DEBIT,CREDIT,CAPTURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.movements[0].entryType").value("DEBIT"))
                .andExpect(jsonPath("$.movements[1].entryType").value("CREDIT"));
    }

    @Test
    @DisplayName("an unknown types value is a 400 — a typo must not read as 'nothing moved'")
    void typeFilter_unknownType_isRejected() throws Exception {
        call(get(PATH, PARTNER).param("from", DAY_START.toString()).param("to", DAY_END.toString())
                        .param("types", "DEBIT,DEDUCTION"))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------------------
    // Pagination — the property the old 500-row clamp could not offer
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("640 movements (past the old 500 cap) are paged in full with no loss and no duplicates")
    void pagination_coversMoreThanTheOldCap() throws Exception {
        int total = 640;
        List<LedgerEntryEntity> batch = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            // Deliberately only 8 distinct instants across 640 rows: heavy timestamp collision, so a
            // page boundary landing mid-instant would repeat or drop rows without the id tie-break.
            Instant at = DAY_START.plusSeconds(i % 8);
            batch.add(new LedgerEntryEntity(PARTNER, "P-" + i, "DEBIT",
                    new BigDecimal("1.00000000"), "USD", at));
        }
        ledger.saveAll(batch);

        Set<String> seen = new HashSet<>();
        int size = 250;
        int page = 0;
        boolean hasNext = true;
        long reportedTotal = -1;
        while (hasNext) {
            MvcResult res = call(get(PATH, PARTNER)
                            .param("from", DAY_START.toString()).param("to", DAY_END.toString())
                            .param("page", String.valueOf(page)).param("size", String.valueOf(size)))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode root = json.readTree(res.getResponse().getContentAsString());
            reportedTotal = root.get("totalElements").asLong();
            hasNext = root.get("hasNext").asBoolean();
            for (JsonNode m : root.get("movements")) {
                // add() returning false ⇒ the same row came back on two pages
                org.assertj.core.api.Assertions
                        .assertThat(seen.add(m.get("txnRef").asText()))
                        .as("txnRef %s was returned twice", m.get("txnRef").asText())
                        .isTrue();
            }
            page++;
            org.assertj.core.api.Assertions.assertThat(page).isLessThan(20); // loop guard
        }
        org.assertj.core.api.Assertions.assertThat(reportedTotal).isEqualTo(total);
        org.assertj.core.api.Assertions.assertThat(seen).hasSize(total);
        org.assertj.core.api.Assertions.assertThat(page).isEqualTo(3); // 250 + 250 + 140
    }

    @Test
    @DisplayName("size is clamped to 1000, and the page still reports the true total")
    void pagination_sizeIsClampedButTotalIsHonest() throws Exception {
        for (int i = 0; i < 10; i++) {
            entry("C-" + i, "DEBIT", "1.00", DAY_START.plusSeconds(i));
        }
        call(get(PATH, PARTNER).param("from", DAY_START.toString()).param("to", DAY_END.toString())
                        .param("size", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1000))
                .andExpect(jsonPath("$.totalElements").value(10))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("unknown partner → empty page with totalElements=0, not an error")
    void unknownPartner_isEmptyNotAnError() throws Exception {
        call(get(PATH, "NOPE").param("from", DAY_START.toString()).param("to", DAY_END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.movements.length()").value(0));
    }

    @Test
    @DisplayName("the echoed window and page metadata let a response be audited without the request")
    void response_echoesTheAppliedWindow() throws Exception {
        call(get(PATH, PARTNER).param("from", DAY_START.toString()).param("to", DAY_END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-07-27T15:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-07-28T15:00:00Z"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(200));
    }

    @Test
    @DisplayName("the existing /deductions endpoint is untouched (other callers bind it)")
    void deductionsEndpointStillWorks() throws Exception {
        entry("D-1", "DEBIT", "9.00000000", DAY_START.plusSeconds(1));
        call(get("/v1/prefunding/{c}/deductions", PARTNER).param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].txnRef").value("D-1"));
    }
}
