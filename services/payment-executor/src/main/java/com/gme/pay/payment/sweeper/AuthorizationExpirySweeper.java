package com.gme.pay.payment.sweeper;

import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.persistence.PaymentAuthorizationEntity;
import com.gme.pay.payment.persistence.PaymentAuthorizationRepository;
import com.gme.pay.payment.service.PaymentAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Reclaims float reservations from authorizations that were never confirmed (abandoned cart, partner
 * crash, lost confirm). Without this the {@code expires_at} TTL is cosmetic and a held reservation
 * leaks forever, permanently shrinking the partner's available float — the MEDIUM availability-leak
 * the spine review flagged (SETTLEMENT_FLOW_SPEC §4.1 / Step 3).
 *
 * <p>For each AUTHORIZED row past its window it ATOMICALLY claims {@code AUTHORIZED → EXPIRED} (the
 * same compare-and-set the confirm path uses) so it can never race a concurrent confirm: if a confirm
 * already moved the row to CONFIRMING/terminal, the claim loses and the sweeper skips it. Only the
 * claim winner voids the authorization (release the hold + fail the orphan PENDING txn). The status
 * filter means in-flight (CONFIRMING) and UNCERTAIN rows are never touched — UNCERTAIN holds belong to
 * the reconciler, not the sweeper.
 *
 * <p>Disabled by default; enable with {@code gmepay.payments.authz.sweeper.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "gmepay.payments.authz.sweeper.enabled", havingValue = "true")
public class AuthorizationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationExpirySweeper.class);

    private final PaymentAuthorizationRepository repository;
    private final PaymentAuthorizationService authorizationService;
    private final PaymentOrchestrator orchestrator;
    private final int batchSize;

    public AuthorizationExpirySweeper(PaymentAuthorizationRepository repository,
                                      PaymentAuthorizationService authorizationService,
                                      PaymentOrchestrator orchestrator,
                                      @Value("${gmepay.payments.authz.sweeper.batch-size:200}") int batchSize) {
        this.repository = repository;
        this.authorizationService = authorizationService;
        this.orchestrator = orchestrator;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${gmepay.payments.authz.sweeper.interval-ms:60000}")
    public void sweepExpired() {
        sweepOnce(Instant.now());
    }

    /** One sweep pass (package-visible for tests). Returns the number of holds actually released. */
    int sweepOnce(Instant now) {
        List<PaymentAuthorizationEntity> expired = repository.findByStatusAndExpiresAtBefore(
                PaymentAuthorizationEntity.STATUS_AUTHORIZED, now, PageRequest.of(0, batchSize));
        int swept = 0;
        for (PaymentAuthorizationEntity a : expired) {
            // Atomic claim AUTHORIZED -> EXPIRED; loser (a concurrent confirm took it) is skipped.
            boolean won = authorizationService.compareAndSetStatus(a.getAuthId(),
                    PaymentAuthorizationEntity.STATUS_AUTHORIZED,
                    PaymentAuthorizationEntity.STATUS_EXPIRED);
            if (!won) {
                continue;
            }
            PartnerType partnerType = PartnerType.valueOf(a.getPartnerType().toUpperCase());
            orchestrator.voidAuthorization(a.getPartnerId(), a.getTxnRef(), partnerType);
            swept++;
        }
        if (swept > 0) {
            log.info("authorization sweeper expired+released {} abandoned hold(s)", swept);
        }
        return swept;
    }
}
