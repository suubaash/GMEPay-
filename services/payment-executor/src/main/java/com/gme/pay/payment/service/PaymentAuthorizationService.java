package com.gme.pay.payment.service;

import com.gme.pay.payment.persistence.PaymentAuthorizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Owns the ATOMIC state transitions of a two-phase payment authorization, so the confirm path can
 * claim an authorization exactly once before the irreversible scheme submit (SETTLEMENT_FLOW_SPEC
 * §7.1). Each method is its own short transaction that commits before any remote call the caller
 * then makes — the row is never locked across a network hop.
 */
@Service
public class PaymentAuthorizationService {

    private final PaymentAuthorizationRepository repository;

    public PaymentAuthorizationService(PaymentAuthorizationRepository repository) {
        this.repository = repository;
    }

    /**
     * Atomically transition {@code from → to} via a single conditional UPDATE. Returns true iff this
     * caller won the claim (rows updated == 1). Concurrent callers see false and must not proceed.
     */
    @Transactional
    public boolean compareAndSetStatus(String authId, String from, String to) {
        return repository.compareAndSetStatus(authId, from, to) == 1;
    }

    /** Records a terminal outcome (status + wallet-charge ref + confirmedAt) in one update. */
    @Transactional
    public void markOutcome(String authId, String status, String walletChargeRef, Instant confirmedAt) {
        repository.markOutcome(authId, status, walletChargeRef, confirmedAt);
    }
}
