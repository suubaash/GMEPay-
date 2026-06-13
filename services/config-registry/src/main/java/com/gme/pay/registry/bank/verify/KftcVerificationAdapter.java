package com.gme.pay.registry.bank.verify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production {@link AccountVerificationProvider} placeholder for KFTC's
 * 계좌실명조회 (account-holder real-name check) rail. Activated by
 * {@code gmepay.account-verification.provider=kftc} — the same
 * property-selected adapter wiring as {@code RestKybClient} — and currently
 * fails loudly: GME's production certificate for the KFTC open-banking
 * channel has not been provisioned yet, and a silent fake here would let an
 * environment believe accounts were KFTC-verified when they were not.
 *
 * <p>When the certificate lands this class gains the HTTP client + signing
 * config; the port, the service write path, the audit trail and the V012
 * roster all stay unchanged (the verdict is already modelled as
 * {@code KFTC_VERIFIED}).
 */
@Component
@ConditionalOnProperty(name = "gmepay.account-verification.provider", havingValue = "kftc")
public class KftcVerificationAdapter implements AccountVerificationProvider {

    @Override
    public VerificationResult verify(AccountRef accountRef) {
        throw new UnsupportedOperationException("KFTC 계좌실명조회 production certificate pending");
    }
}
