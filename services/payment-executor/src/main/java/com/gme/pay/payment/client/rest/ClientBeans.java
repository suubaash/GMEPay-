package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.domain.settlement.SettlementBookingService;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

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

    /**
     * Switches the autoconfigured {@link org.springframework.web.client.RestClient.Builder} from the
     * default {@code SimpleClientHttpRequestFactory} (JDK {@code HttpURLConnection}) to
     * {@link JdkClientHttpRequestFactory} (backed by {@code java.net.http.HttpClient}).
     *
     * <p>Rationale: {@code HttpURLConnection} rejects the {@code PATCH} verb with
     * {@code ProtocolException: Invalid HTTP method: PATCH}, which broke
     * {@code RestTransactionClient.commitStatus} ({@code PATCH /v1/transactions/{ref}/status}) on the
     * live orchestration path — the scheme would capture but the local status commit would fail. The
     * JDK {@code HttpClient} supports arbitrary methods including PATCH. Applies to every adapter that
     * autowires the shared builder (transaction/rate/qr/scheme clients).
     */
    @Bean
    public RestClientCustomizer patchCapableRequestFactoryCustomizer() {
        return builder -> builder.requestFactory(new JdkClientHttpRequestFactory());
    }

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
