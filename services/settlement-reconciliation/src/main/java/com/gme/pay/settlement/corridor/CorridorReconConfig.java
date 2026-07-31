package com.gme.pay.settlement.corridor;

import com.gme.pay.settlement.alert.ReconBreakAlerter;
import com.gme.pay.settlement.persistence.CorridorReconSummaryRepository;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.port.PrefundingMovementPort;
import com.gme.pay.settlement.port.SchemeReconFeedParser;
import com.gme.pay.settlement.port.SchemeSettlementPort;
import com.gme.pay.settlement.port.SchemeTransactionPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Wires the cross-border three-way reconciliation (GAP T2-2), one bean per corridor.
 *
 * <p><b>SENDMN only.</b> 9Pay is intentionally absent: its adapter is production-shaped but nothing
 * on the hub orchestrates a 9Pay payout yet (register item T4-7, awaiting a product decision), so
 * there are no hub-side payouts, no prefunding movements and no transactions for a 9Pay tie-out to
 * reconcile. When that decision lands, 9Pay is a second {@link CorridorSpec} + its three ports +
 * one more {@code @Bean} here — the engine, the ops exception queue, the alerter and the daily
 * summary are all corridor-agnostic already.
 */
@Configuration
public class CorridorReconConfig {

    /** Upper-case lane code for the SendMN (KRW→MNT) corridor. */
    public static final String SENDMN = "SENDMN";

    /**
     * The SENDMN corridor's parameters. Defaults mirror what payment-executor's SENDMN path actually
     * does today (₩500 fixed service fee, the 1350 KRW/USD fallback constant) so the back-derived
     * rate basis is comparable; every value is overridable per environment because they are
     * observations of another service's behaviour, not settings this service owns.
     */
    @Bean
    public CorridorSpec sendmnCorridorSpec(
            @Value("${gmepay.settlement.corridor.sendmn.service-fee-krw:500}") BigDecimal serviceFeeKrw,
            @Value("${gmepay.settlement.corridor.sendmn.fallback-krw-per-usd:1350}") BigDecimal fallbackKrwPerUsd,
            @Value("${gmepay.settlement.corridor.sendmn.fallback-match-tolerance-krw:0.5}") BigDecimal fallbackTolerance,
            @Value("${gmepay.settlement.corridor.sendmn.rate-basis-tolerance-usd:0}") BigDecimal rateBasisToleranceUsd) {
        return new CorridorSpec(SENDMN, "sendmn", SENDMN, "KRW->MNT", "MNT",
                serviceFeeKrw, fallbackKrwPerUsd, fallbackTolerance, rateBasisToleranceUsd);
    }

    /**
     * SendMN's recon FILE format is undefined (external gate O4), so the feed leg is registered as an
     * explicit "pending" parser. It makes the reconciler stamp {@code scheme_feed_available=false} on
     * every daily summary, which is how a reader tells an internal-only tie-out from a
     * partner-confirmed one.
     */
    @Bean
    public SchemeReconFeedParser sendmnReconFeedParser() {
        return new PendingFormatReconFeedParser(SENDMN, "O4 — SendMN recon file format is not documented");
    }

    /** The SENDMN three-way reconciler. */
    @Bean
    public CorridorThreeWayReconciler sendmnThreeWayReconciler(
            CorridorSpec sendmnCorridorSpec,
            SchemeTransactionPort transactionPort,
            PrefundingMovementPort prefundingPort,
            SchemeSettlementPort schemePort,
            SchemeReconFeedParser sendmnReconFeedParser,
            ReconExceptionRepository exceptionRepository,
            CorridorReconSummaryRepository summaryRepository,
            ReconBreakAlerter breakAlerter) {
        return new CorridorThreeWayReconciler(sendmnCorridorSpec, transactionPort, prefundingPort,
                schemePort, sendmnReconFeedParser, exceptionRepository, summaryRepository, breakAlerter);
    }
}
