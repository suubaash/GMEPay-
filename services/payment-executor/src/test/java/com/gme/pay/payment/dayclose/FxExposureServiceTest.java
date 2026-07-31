package com.gme.pay.payment.dayclose;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.payment.domain.client.TransactionClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>T2-5 / CFO#9 proof</b> for the derived FX position: "GME bears FX risk on MNT/VND/KRW with no
 * measurement".
 *
 * <p>The fixture is deliberately multi-currency and deliberately awkward — three corridors, one transaction with
 * no recorded USD deduction, and one priced at exactly the hub's hardcoded 1350 KRW/USD fallback — because the
 * easy version of this measurement (sum the amounts per currency) gets every one of those cases wrong: it nets
 * MNT against KRW, it assumes a rate for the transaction that has none, and it cannot tell a live rate from a
 * compile-time constant.
 *
 * <p>{@link #nothingIsInventedWhenThereIsNothingToDeriveFrom()} and
 * {@link #anUnreadableTransactionLegIsUNAVAILABLENotZero()} pin the two lines this report must not cross:
 * it never fabricates a rate, and it never renders "we could not look" as "there is no exposure".
 */
class FxExposureServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T02:00:00Z");
    private static final LocalDate D = LocalDate.of(2026, 7, 28);

    /** A transaction client serving a canned day, or throwing. */
    private static TransactionClient client(Map<LocalDate, List<TransactionClient.DailyTransaction>> byDate) {
        return new TransactionClient() {
            @Override
            public CreateResult createPending(CreateRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void commitStatus(String txnRef, StatusPatch patch) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DailyTransaction> findApprovedForDate(LocalDate businessDate) {
                return byDate.getOrDefault(businessDate, List.of());
            }
        };
    }

    private static TransactionClient throwingClient() {
        return new TransactionClient() {
            @Override
            public CreateResult createPending(CreateRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void commitStatus(String txnRef, StatusPatch patch) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DailyTransaction> findApprovedForDate(LocalDate businessDate) {
                throw new IllegalStateException("transaction-mgmt unreachable");
            }
        };
    }

    private static TransactionClient.DailyTransaction txn(String ref, String scheme, String collCcy,
                                                         String collAmt, String payCcy, String payAmt,
                                                         String usd, String refunded) {
        return new TransactionClient.DailyTransaction(ref, scheme, collCcy, new BigDecimal(collAmt), payCcy,
                new BigDecimal(payAmt), usd == null ? null : new BigDecimal(usd), null,
                refunded == null ? null : new BigDecimal(refunded), NOW);
    }

    private FxExposureService serviceFor(TransactionClient client) {
        return new FxExposureService(client, null, Clock.fixed(NOW, ZoneOffset.UTC), 7,
                "KRW", new BigDecimal("1350"), BigDecimal.ONE);
    }

    private static FxExposureReport.CurrencyPosition position(FxExposureReport report, String ccy) {
        return report.positions().stream()
                .filter(p -> ccy.equals(p.currency()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no position reported for " + ccy
                        + "; got " + report.positions().stream()
                        .map(FxExposureReport.CurrencyPosition::currency).toList()));
    }

    @Test
    @DisplayName("multi-currency fixture: each currency is netted against ITSELF and nothing else")
    void multiCurrencyFixtureIsMeasuredPerCurrency() {
        FxExposureReport report = serviceFor(client(Map.of(D, List.of(
                // KRW 1,350,000 collected, MNT 3,000,000 paid out, USD 1,000 settled => 1350 KRW/USD basis.
                txn("T1", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000", null),
                // KRW 1,300,000 collected, VND 24,000,000 paid, USD 1,000 settled => 1300 KRW/USD basis.
                txn("T2", "ninepay", "KRW", "1300000", "VND", "24000000", "1000", null),
                // USD-collected NPR payout, with a 5 USD refund already recorded against it.
                txn("T3", "nepal", "USD", "500", "NPR", "66000", "500", "5")))))
                .measure(D, D);

        assertThat(report.available()).isTrue();
        assertThat(report.transactionCount()).isEqualTo(3);
        assertThat(report.positions()).hasSize(5);      // KRW, MNT, VND, USD, NPR

        FxExposureReport.CurrencyPosition krw = position(report, "KRW");
        assertThat(krw.collected()).isEqualByComparingTo("2650000");
        assertThat(krw.collectedTxnCount()).isEqualTo(2);
        assertThat(krw.paidOut()).isEqualByComparingTo("0");
        assertThat(krw.netOpenPosition())
                .as("collected in KRW and never paid out in KRW => a LONG KRW position")
                .isEqualByComparingTo("2650000");
        assertThat(krw.settledUsd()).isEqualByComparingTo("2000");
        assertThat(krw.impliedUsdBasis())
                .as("2,650,000 KRW settled through 2,000 USD => 1325 KRW/USD, back-derived not looked up")
                .isEqualByComparingTo("1325.0000");
        assertThat(krw.basisTxnCount()).isEqualTo(2);
        assertThat(krw.missingBasisCount()).isZero();
        assertThat(krw.fallbackBasisSuspected())
                .as("T1 alone prices at exactly the 1350 hardcoded fallback")
                .isEqualTo(1);

        FxExposureReport.CurrencyPosition mnt = position(report, "MNT");
        assertThat(mnt.paidOut()).isEqualByComparingTo("3000000");
        assertThat(mnt.netOpenPosition())
                .as("paid out in MNT and never collected in MNT => a SHORT MNT position; the exposure")
                .isEqualByComparingTo("-3000000");
        assertThat(mnt.settledUsd())
                .as("the same USD movement settled this leg too")
                .isEqualByComparingTo("1000");
        assertThat(mnt.impliedUsdBasis())
                .as("MNT was never a collection currency, so there is no collection basis to derive")
                .isNull();

        FxExposureReport.CurrencyPosition vnd = position(report, "VND");
        assertThat(vnd.netOpenPosition()).isEqualByComparingTo("-24000000");
        assertThat(vnd.fallbackBasisSuspected())
                .as("the KRW-shaped fallback constant is never applied to another currency")
                .isZero();

        FxExposureReport.CurrencyPosition usd = position(report, "USD");
        assertThat(usd.collected()).isEqualByComparingTo("500");
        assertThat(usd.refunded()).isEqualByComparingTo("5");
        assertThat(usd.netOpenPosition())
                .as("a refund reduces the position it was collected into")
                .isEqualByComparingTo("495");

        assertThat(position(report, "NPR").netOpenPosition()).isEqualByComparingTo("-66000");
    }

    @Test
    @DisplayName("a transaction with no recorded USD is COUNTED as unknown-basis, never assigned one")
    void nothingIsInventedWhenThereIsNothingToDeriveFrom() {
        FxExposureReport report = serviceFor(client(Map.of(D, List.of(
                txn("T1", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000", null),
                txn("T2", "sendmn", "KRW", "1350000", "MNT", "3000000", null, null)))))
                .measure(D, D);

        FxExposureReport.CurrencyPosition krw = position(report, "KRW");
        assertThat(krw.collected()).as("the amount is still counted").isEqualByComparingTo("2700000");
        assertThat(krw.settledUsd()).as("but no USD is invented for it").isEqualByComparingTo("1000");
        assertThat(krw.basisTxnCount()).isEqualTo(1);
        assertThat(krw.missingBasisCount())
                .as("a non-zero count tells the reader the basis does not describe the whole period")
                .isEqualTo(1);
        // The basis is derived from the sample that HAS a USD figure, not from the whole collected amount.
        assertThat(krw.impliedUsdBasis()).isEqualByComparingTo("2700.0000");
    }

    @Test
    @DisplayName("an unreadable transaction leg reports UNAVAILABLE with no positions — not a zero position")
    void anUnreadableTransactionLegIsUNAVAILABLENotZero() {
        FxExposureReport report = serviceFor(throwingClient()).measure(D, D);

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).contains("transaction-mgmt unreachable");
        assertThat(report.positions())
                .as("'we could not look' and 'there is no exposure' must never render identically")
                .isEmpty();
        assertThat(report.transactionCount()).isZero();
    }

    @Test
    @DisplayName("a multi-day window accumulates, because exposure is cumulative not daily")
    void aMultiDayWindowAccumulates() {
        LocalDate d2 = D.plusDays(1);
        FxExposureReport report = serviceFor(client(Map.of(
                D, List.of(txn("T1", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000", null)),
                d2, List.of(txn("T2", "sendmn", "KRW", "1350000", "MNT", "3000000", "1000", null)))))
                .measure(D, d2);

        assertThat(report.transactionCount()).isEqualTo(2);
        assertThat(position(report, "MNT").netOpenPosition())
                .as("one day's MNT payout says little; the same payout every day is the position")
                .isEqualByComparingTo("-6000000");
    }
}
