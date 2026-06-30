package com.gme.pay.qr.persistence;

import com.gme.pay.qr.domain.cpm.CpmSessionStorePort;
import com.gme.pay.qr.domain.cpm.CpmToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for {@link CpmSessionStorePort} backed by the {@code cpm_prepare_session} table
 * (Flyway V003, WBS 5.3-T01 / 5.3-T10).
 */
@Component
public class JpaCpmSessionStoreAdapter implements CpmSessionStorePort {

    private static final String STATUS_ISSUED  = "ISSUED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final CpmPrepareSessionRepository repository;

    public JpaCpmSessionStoreAdapter(CpmPrepareSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByPartnerTxnRef(String partnerTxnRef) {
        return repository.existsByPartnerTxnRef(partnerTxnRef);
    }

    @Override
    @Transactional
    public void save(CpmToken token, String direction, String countryCode, String customerRef,
                     boolean schemeIssued, PrefundReservation reservation) {
        Instant now = Instant.now();
        repository.save(new CpmPrepareSessionEntity(
                token.cpmTokenId(),
                token.paymentId(),
                token.schemeId(),
                direction,
                countryCode,
                customerRef,
                token.partnerTxnRef(),
                token.prepareToken(),
                token.qrContent(),
                STATUS_ISSUED,
                schemeIssued,
                reservation == null ? null : reservation.reservedUsd(),
                reservation == null ? null : reservation.partnerId(),
                reservation == null ? null : reservation.reservationId(),
                token.issuedAt(),
                token.expiresAt(),
                now));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CpmToken> findByPaymentId(String paymentId) {
        return repository.findByPaymentId(paymentId).map(JpaCpmSessionStoreAdapter::toDomain);
    }

    @Override
    @Transactional
    public List<ExpiredSession> expireOverdue(Instant cutoff) {
        List<CpmPrepareSessionEntity> overdue =
                repository.findByStatusAndExpiresAtBefore(STATUS_ISSUED, cutoff);
        List<ExpiredSession> expired = new ArrayList<>(overdue.size());
        Instant now = Instant.now();
        for (CpmPrepareSessionEntity e : overdue) {
            e.setStatus(STATUS_EXPIRED);
            e.setUpdatedAt(now);
            expired.add(new ExpiredSession(
                    e.getCpmTokenId(), e.getPrefundPartnerId(), e.getPrefundReservationId()));
        }
        repository.saveAll(overdue);
        return expired;
    }

    private static CpmToken toDomain(CpmPrepareSessionEntity e) {
        return new CpmToken(
                e.getCpmTokenId(), e.getPaymentId(), e.getPrepareToken(), e.getQrContent(),
                e.getSchemeId(), e.getPartnerTxnRef(), e.getIssuedAt(), e.getExpiresAt());
    }
}
