package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.domain.settlement.SettlementBookingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link PaymentOrchestrator} together with the REST client adapters.
 *
 * <p>Each REST adapter is autowired by interface — because the {@code Rest*Client}
 * beans are annotated {@code @Primary} they win over any other implementation present
 * (e.g. future stub beans) without needing to remove the stubs from the classpath.
 *
 * <p>The {@link org.springframework.web.client.RestClient.Builder} used by each adapter
 * is the one autoconfigured by Spring Boot, which is wired with the application's
 * {@code ObjectMapper} (already carrying {@code JavaTimeModule} for {@link java.time.Instant}
 * round-tripping).
 */
@Configuration
public class ClientBeans {

    /*
     * There used to be a `patchCapableRequestFactoryCustomizer` bean here:
     *
     *     return builder -> builder.requestFactory(new JdkClientHttpRequestFactory());
     *
     * It existed because HttpURLConnection (Boot's default SimpleClientHttpRequestFactory) rejects
     * the PATCH verb with `ProtocolException: Invalid HTTP method: PATCH`, which broke
     * RestTransactionClient.commitStatus (PATCH /v1/transactions/{ref}/status) on the live
     * orchestration path — the scheme would capture but the local status commit would fail.
     *
     * T3-11 DELETED it, and the deletion is the fix, not a cleanup. That factory carried NO
     * timeouts, so every client fed by the shared builder — transaction, rate, qr, prefunding,
     * revenue-ledger, partner-config, smart-router — was unbounded on read: a peer that accepted the
     * connection and then went quiet held a Tomcat worker until the OS closed the socket. That is
     * the mechanism `RUNBOOK_LOAD_AND_CAPACITY.md` §4.1 #7 identified as the manufacturer of
     * UNCERTAIN payments under load.
     *
     * Both concerns are now met by ONE mechanism instead of two competing ones:
     * com.gme.pay.http.HttpClientTimeoutAutoConfiguration (lib-errors) installs a
     * JdkClientHttpRequestFactory — still PATCH-capable, that property is preserved — carrying a
     * connect and a read timeout, on every service in the fleet.
     *
     * Two customizers both calling `builder.requestFactory(...)` is a race decided by bean ordering,
     * where the loser is silently discarded; the failure mode of getting it wrong is not an error but
     * an unbounded money-path client that looks configured. So payment-executor no longer registers
     * a competing customizer. It tightens the platform floor by PROPERTY instead — see
     * `gmepay.http.client.*` in application.properties, where the 5 s read budget and the reasoning
     * for it are stated. Pinned by InternalHttpTimeoutTest.
     */

    /**
     * Constructs the {@link PaymentOrchestrator} using the {@code @Primary} REST adapters
     * (Spring picks the {@code Rest*Client} bean for each interface).
     *
     * <p>Wires the full 7-arg constructor so the per-partner settlement booking (step 6)
     * and the rounding-residual post to revenue-ledger (step 8) actually run — the prior
     * 5-arg wiring injected {@code null} for both collaborators, silently skipping those
     * steps on every orchestrated payment.
     */
    @Bean
    public PaymentOrchestrator paymentOrchestrator(
            RateClient rateClient,
            PrefundingClient prefundingClient,
            QrClient qrClient,
            SchemeClient schemeClient,
            TransactionClient transactionClient,
            SettlementBookingService settlementBookingService,
            RevenueLedgerClient revenueLedgerClient,
            com.gme.pay.payment.domain.client.PartnerConfigClient partnerConfigClient) {
        return new PaymentOrchestrator(
                rateClient, prefundingClient, qrClient, schemeClient, transactionClient,
                settlementBookingService, revenueLedgerClient, partnerConfigClient);
    }
}
