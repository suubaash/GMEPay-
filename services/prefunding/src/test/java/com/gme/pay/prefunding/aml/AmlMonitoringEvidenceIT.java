package com.gme.pay.prefunding.aml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerEntity;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import com.gme.pay.prefunding.testsupport.TestInternalAuth;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Gap T5-3 — prefunding's cumulative-usage ledger read as AML monitoring EVIDENCE over a window.
 *
 * <p>The ledger (V006) was already the platform's only append-only record of per-partner volume and
 * velocity, and it was queryable in exactly one shape: the single-period sums the authorize path
 * compares against a cap. This class pins the read that was missing — day by day across a window,
 * with the partner's configured caps as context — and pins the bounds that stop it becoming a scan
 * of the whole ledger.
 *
 * <p>The seeded fixture is built so that each assertion can only pass for the right reason:
 * <ul>
 *   <li>a day with TWO charges (net must aggregate within a day, not report one row per entry);</li>
 *   <li>a day whose single charge is fully REVERSED (net must go to zero while the raw charge count
 *       stays at one — a day of activity that nets out is not an idle day);</li>
 *   <li>a day inside the window with NO rows (absent, not present-and-zero);</li>
 *   <li>rows on the days immediately before and after the window (inclusive bounds must exclude
 *       them — an off-by-one here would silently import a neighbour's volume into the evidence).</li>
 * </ul>
 *
 * <p>No rules are configured in this context, which is the shipped default, so nothing here alerts.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AmlMonitoringEvidenceIT {

    private static final String PARTNER = "AML_EV_P1";

    @Autowired private AmlMonitoringService monitoring;
    @Autowired private CumulativeUsageLedgerRepository cumulative;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private MockMvc mvc;

    @BeforeEach
    void seed() {
        cumulative.deleteAll();
        balances.deleteAll();

        PartnerBalanceEntity partner = new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("1000000.00000000"), null, Instant.now());
        partner.setAmlDailyCapUsd(new BigDecimal("5000.0000"));
        partner.setAmlMonthlyCapUsd(new BigDecimal("100000.0000"));
        // annual cap left NULL on purpose: "unconstrained" must survive the round trip as null and
        // not be defaulted to zero, which would read as the strictest possible cap.
        partner.setAmlDailyTxnCountCap(25);
        balances.save(partner);

        charge("2026-06-30", "T-BEFORE", "999");   // outside the window (lower side)
        charge("2026-07-01", "T1", "100");
        charge("2026-07-01", "T2", "200");
        charge("2026-07-02", "T3", "500");
        reverse("2026-07-02", "T3", "500");        // nets 07-02 back to zero
        charge("2026-07-03", "T4", "50");
        // 2026-07-04: nothing at all
        charge("2026-07-05", "T5", "25");
        charge("2026-07-06", "T-AFTER", "777");    // outside the window (upper side)
    }

    // -------------------------------------------------------------------------
    // the evidence itself
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the window returns per-day net USD, net + raw counts, and window totals — reverses netted, neighbours excluded")
    void windowEvidence() {
        AmlWindowEvidence e = monitoring.evidence(PARTNER, "2026-07-01", "2026-07-05");

        assertThat(e.partnerId()).isEqualTo(PARTNER);
        assertThat(e.fromDailyKey()).isEqualTo("2026-07-01");
        assertThat(e.toDailyKey()).isEqualTo("2026-07-05");
        assertThat(e.spanDays()).isEqualTo(5);

        // Four rows for five days: 2026-07-04 had no activity and is therefore ABSENT, not zero.
        assertThat(e.days()).extracting(AmlWindowEvidence.DayUsage::dailyKey)
                .containsExactly("2026-07-01", "2026-07-02", "2026-07-03", "2026-07-05");

        AmlWindowEvidence.DayUsage d1 = e.days().get(0);
        assertThat(d1.netUsd()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("300"));
        assertThat(d1.netTxnCount()).isEqualTo(2);
        assertThat(d1.chargeCount()).isEqualTo(2);

        // The reversed day: nets to zero volume and zero velocity, but one charge WAS attempted.
        AmlWindowEvidence.DayUsage d2 = e.days().get(1);
        assertThat(d2.netUsd()).usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
        assertThat(d2.netTxnCount()).isZero();
        assertThat(d2.chargeCount())
                .as("a fully reversed day nets out, but the attempt is still visible")
                .isEqualTo(1);

        assertThat(e.days().get(2).netUsd()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("50"));
        assertThat(e.days().get(3).netUsd()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("25"));

        // 300 + 0 + 50 + 25 — the 999 on 06-30 and the 777 on 07-06 are outside the bounds.
        assertThat(e.windowNetUsd()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("375"));
        assertThat(e.windowNetTxnCount()).isEqualTo(4);
        assertThat(e.maxDailyNetUsd()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("300"));
        assertThat(e.maxDailyNetTxnCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the partner's CONFIGURED caps ride along as context; an unset cap stays null (unconstrained), not zero")
    void configuredCapsAreReportedAsContext() {
        AmlWindowEvidence.ConfiguredCaps caps =
                monitoring.evidence(PARTNER, "2026-07-01", "2026-07-05").caps();

        assertThat(caps.dailyCapUsd()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("5000"));
        assertThat(caps.monthlyCapUsd()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("100000"));
        assertThat(caps.annualCapUsd())
                .as("unconstrained must not be reported as a cap of zero")
                .isNull();
        assertThat(caps.dailyTxnCountCap()).isEqualTo(25);
    }

    @Test
    @DisplayName("a single-day window is legal and inclusive on both ends")
    void singleDayWindow() {
        AmlWindowEvidence e = monitoring.evidence(PARTNER, "2026-07-01", "2026-07-01");
        assertThat(e.spanDays()).isEqualTo(1);
        assertThat(e.days()).hasSize(1);
        assertThat(e.windowNetUsd()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("300"));
    }

    @Test
    @DisplayName("a window with no activity is empty but well-formed — zero totals, no rows")
    void quietWindow() {
        AmlWindowEvidence e = monitoring.evidence(PARTNER, "2026-08-01", "2026-08-31");
        assertThat(e.days()).isEmpty();
        assertThat(e.windowNetUsd()).usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
        assertThat(e.windowNetTxnCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // the window cannot be abused
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an INVERTED range is a 400, never a silently empty result")
    void invertedWindowRejected() {
        assertThatThrownBy(() -> monitoring.evidence(PARTNER, "2026-07-05", "2026-07-01"))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("an OVERSIZED window is rejected — the read surface is not a full-table scan")
    void oversizedWindowRejected() {
        assertThatThrownBy(() -> monitoring.evidence(PARTNER, "2020-01-01", "2026-07-30"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("max-window-days");
    }

    @Test
    @DisplayName("a window of exactly max-window-days (366) is allowed — the bound is inclusive")
    void maximumWindowAllowed() {
        // 2026-01-01 + 365 days = 2026-12-31 -> 365 days inclusive; add one more to reach 366.
        AmlWindowEvidence e = monitoring.evidence(PARTNER, "2026-01-01", "2027-01-01");
        assertThat(e.spanDays()).isEqualTo(366);
    }

    @Test
    @DisplayName("a malformed or non-existent date is a 400 — never smeared onto a nearby day")
    void malformedDateRejected() {
        assertThatThrownBy(() -> monitoring.evidence(PARTNER, "2026-02-30", "2026-03-01"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> monitoring.evidence(PARTNER, "01/07/2026", "2026-07-05"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an unknown partner is an error, not an empty window that reads as 'transacted nothing'")
    void unknownPartnerRejected() {
        assertThatThrownBy(() -> monitoring.evidence("NO_SUCH_PARTNER", "2026-07-01", "2026-07-05"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unknown partnerId");
    }

    // -------------------------------------------------------------------------
    // the HTTP surface
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /internal/v1/prefunding/{id}/aml-monitoring serves the evidence behind the internal-auth gate")
    void httpEvidenceEndpoint() throws Exception {
        mvc.perform(TestInternalAuth.authed(
                        get("/internal/v1/prefunding/{p}/aml-monitoring", PARTNER)
                                .param("from", "2026-07-01")
                                .param("to", "2026-07-05")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(PARTNER))
                .andExpect(jsonPath("$.from").value("2026-07-01"))
                .andExpect(jsonPath("$.to").value("2026-07-05"))
                .andExpect(jsonPath("$.days.length()").value(4))
                .andExpect(jsonPath("$.daysWithActivity").value(4))
                .andExpect(jsonPath("$.days[0].dailyKey").value("2026-07-01"))
                .andExpect(jsonPath("$.days[0].netTxnCount").value(2))
                .andExpect(jsonPath("$.days[1].netTxnCount").value(0))
                .andExpect(jsonPath("$.days[1].chargeCount").value(1))
                .andExpect(jsonPath("$.windowNetTxnCount").value(4))
                .andExpect(jsonPath("$.configuredCaps.dailyTxnCountCap").value(25))
                .andExpect(jsonPath("$.configuredCaps.annualCapUsd").doesNotExist());
    }

    @Test
    @DisplayName("the endpoint is behind the internal-auth gate: no token, no evidence")
    void httpEndpointRequiresInternalToken() throws Exception {
        mvc.perform(get("/internal/v1/prefunding/{p}/aml-monitoring", PARTNER)
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-05"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an inverted range over HTTP is a 400")
    void httpInvertedWindowIsBadRequest() throws Exception {
        mvc.perform(TestInternalAuth.authed(
                        get("/internal/v1/prefunding/{p}/aml-monitoring", PARTNER)
                                .param("from", "2026-07-05")
                                .param("to", "2026-07-01")))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private void charge(String dailyKey, String txnRef, String usd) {
        cumulative.save(new CumulativeUsageLedgerEntity(PARTNER, txnRef, "CUM_CHARGE",
                new BigDecimal(usd), dailyKey, dailyKey.substring(0, 7), dailyKey.substring(0, 4),
                Instant.now()));
    }

    /** A reverse carries the CHARGE's original period keys and a signed-negative amount (V006). */
    private void reverse(String dailyKey, String txnRef, String usd) {
        cumulative.save(new CumulativeUsageLedgerEntity(PARTNER, txnRef, "CUM_REVERSE",
                new BigDecimal(usd).negate(), dailyKey, dailyKey.substring(0, 7),
                dailyKey.substring(0, 4), Instant.now()));
    }
}
