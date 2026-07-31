package com.gme.sim.sendmn.model;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory payment ledger keyed by TX_TOKEN_NO (the scheme's idempotency key).
 * A token that has already reached Processing/Approved is a duplicate for Confirm
 * (error 304); a token only seen by VerifyQr ({@code Decrypted}) may proceed.
 */
@Component
public class PaymentStore {

    private final Map<String, PaymentRecord> byToken = new ConcurrentHashMap<>();
    private final AtomicLong paymentSeq = new AtomicLong(1_000_000L);
    private final AtomicLong receiptSeq = new AtomicLong(1_453_767_200L);

    /** VerifyQr — record/refresh the token as Decrypted (no-op if already confirmed). */
    public void recordDecrypted(String txTokenNo, Merchant merchant) {
        byToken.compute(txTokenNo, (t, existing) -> {
            if (existing == null || existing.getState() == PaymentRecord.State.DECRYPTED) {
                return new PaymentRecord(t, PaymentRecord.State.DECRYPTED, merchant);
            }
            return existing;
        });
    }

    /**
     * Confirm — atomically transitions the token to Processing and assigns
     * PAYMENT_NO / PAYMENT_RECIPT_NO. Returns empty when the token was already
     * confirmed (duplicate → caller answers 304).
     */
    public synchronized Optional<PaymentRecord> confirm(String txTokenNo, Merchant merchant) {
        PaymentRecord existing = byToken.get(txTokenNo);
        if (existing != null && existing.getState() != PaymentRecord.State.DECRYPTED) {
            return Optional.empty();
        }
        PaymentRecord record = existing != null
                ? existing
                : new PaymentRecord(txTokenNo, PaymentRecord.State.DECRYPTED, merchant);
        record.setMerchant(merchant);
        record.setState(PaymentRecord.State.PROCESSING);
        record.setPaymentNo(String.valueOf(paymentSeq.incrementAndGet()));
        record.setPaymentReceiptNo("GME" + receiptSeq.incrementAndGet());
        byToken.put(txTokenNo, record);
        return Optional.of(record);
    }

    public Optional<PaymentRecord> find(String txTokenNo) {
        return Optional.ofNullable(byToken.get(txTokenNo));
    }
}
