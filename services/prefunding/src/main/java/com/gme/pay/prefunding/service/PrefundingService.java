package com.gme.pay.prefunding.service;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.PrefundingAccount;
import com.gme.pay.prefunding.alert.TierAlertEvaluator;
import com.gme.pay.prefunding.persistence.LedgerEntryEntity;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for partner prefunding. Read-only queries do not lock; mutating operations
 * ({@link #deduct(String, String, BigDecimal)} and {@link #credit(String, BigDecimal)}) take a
 * row-level write lock via {@link PartnerBalanceRepository#lockByPartnerId(String)} so concurrent
 * payments are serialized at the DB. The business invariants live in
 * {@link PrefundingAccount} from lib-prefunding.
 *
 * <p>Slice 5: after every balance mutation the {@link TierAlertEvaluator} runs inside the
 * same transaction, so a raised {@code balance_alert} row and its outbox event commit
 * atomically with the new balance (transactional-Outbox contract, ADR-001).
 */
@Service
public class PrefundingService {

    static final String ENTRY_DEBIT = "DEBIT";
    static final String ENTRY_CREDIT = "CREDIT";
    static final String ENTRY_RESERVE = "RESERVE";
    static final String ENTRY_CAPTURE = "CAPTURE";
    static final String ENTRY_RELEASE = "RELEASE";

    private final PartnerBalanceRepository balances;
    private final LedgerEntryRepository ledger;
    private final TierAlertEvaluator tierAlerts;

    public PrefundingService(PartnerBalanceRepository balances, LedgerEntryRepository ledger,
                             TierAlertEvaluator tierAlerts) {
        this.balances = balances;
        this.ledger = ledger;
        this.tierAlerts = tierAlerts;
    }

    /** Returns the current balance for {@code partnerId}, or throws if no such partner exists. */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(String partnerId) {
        PartnerBalanceEntity row = balances.findById(partnerId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "unknown partnerId " + partnerId));
        return row.getBalance();
    }

    /**
     * Atomically deduct {@code amount} from the partner's balance and append a DEBIT ledger entry.
     * Uses SELECT FOR UPDATE; throws {@link ErrorCode#INSUFFICIENT_PREFUNDING} if the balance would
     * go negative (in which case nothing is written).
     */
    @Transactional
    public BigDecimal deduct(String partnerId, String txnRef, BigDecimal amount) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        BigDecimal previousBalance = row.getBalance();
        PrefundingAccount account = toDomain(row);
        BigDecimal newBalance = account.deduct(amount); // throws INSUFFICIENT_PREFUNDING if too low
        Instant now = Instant.now();
        row.setBalance(newBalance);
        row.setUpdatedAt(now);
        PartnerBalanceEntity saved = balances.save(row);
        ledger.save(new LedgerEntryEntity(partnerId, txnRef, ENTRY_DEBIT, amount,
                row.getCurrency(), now));
        tierAlerts.afterBalanceChange(saved, previousBalance);
        return newBalance;
    }

    /** Atomically credit {@code amount} onto the partner's balance and append a CREDIT entry. */
    @Transactional
    public BigDecimal credit(String partnerId, BigDecimal amount) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        BigDecimal previousBalance = row.getBalance();
        PrefundingAccount account = toDomain(row);
        BigDecimal newBalance = account.credit(amount);
        Instant now = Instant.now();
        row.setBalance(newBalance);
        row.setUpdatedAt(now);
        PartnerBalanceEntity saved = balances.save(row);
        ledger.save(new LedgerEntryEntity(partnerId, null, ENTRY_CREDIT, amount,
                row.getCurrency(), now));
        tierAlerts.afterBalanceChange(saved, previousBalance);
        return newBalance;
    }

    /**
     * Atomically reverses a prior deduction identified by {@code txnRef}: credits the originally
     * debited amount back onto the partner's balance and appends a CREDIT entry tagged with the same
     * {@code txnRef} (a regular credit carries a null txnRef, so a CREDIT + txnRef IS the reversal
     * marker — no new entry type / migration needed). Idempotent: if the deduction was already
     * reversed (a CREDIT for this txnRef exists) or no DEBIT exists, nothing is written and the
     * reversed amount is reported as zero.
     *
     * @return the amount actually credited back + the resulting balance
     */
    @Transactional
    public ReverseResult reverse(String partnerId, String txnRef) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        java.util.List<LedgerEntryEntity> entries = ledger.findByPartnerIdAndTxnRef(partnerId, txnRef);
        boolean alreadyReversed = entries.stream().anyMatch(e -> ENTRY_CREDIT.equals(e.getEntryType()));
        BigDecimal debited = entries.stream()
                .filter(e -> ENTRY_DEBIT.equals(e.getEntryType()))
                .map(LedgerEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (alreadyReversed || debited.signum() == 0) {
            // Idempotent no-op: nothing to reverse (or already reversed). Report zero reversed.
            return new ReverseResult(BigDecimal.ZERO, row.getBalance());
        }
        BigDecimal previousBalance = row.getBalance();
        PrefundingAccount account = toDomain(row);
        BigDecimal newBalance = account.credit(debited);
        Instant now = Instant.now();
        row.setBalance(newBalance);
        row.setUpdatedAt(now);
        PartnerBalanceEntity saved = balances.save(row);
        ledger.save(new LedgerEntryEntity(partnerId, txnRef, ENTRY_CREDIT, debited,
                row.getCurrency(), now));
        tierAlerts.afterBalanceChange(saved, previousBalance);
        return new ReverseResult(debited, newBalance);
    }

    /** Outcome of a reverse: the amount credited back and the balance after. */
    public record ReverseResult(BigDecimal reversedAmount, BigDecimal balanceAfter) {}

    private PartnerBalanceEntity lockOrThrow(String partnerId) {
        return balances.lockByPartnerId(partnerId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "unknown partnerId " + partnerId));
    }

    private static PrefundingAccount toDomain(PartnerBalanceEntity row) {
        return new PrefundingAccount(row.getPartnerId(), row.getCurrency(),
                row.getBalance(), row.getReserved(), row.getCreditLimit(),
                row.getLowBalanceThreshold());
    }

    // ---- reservation ledger (two-phase authorize/confirm, SETTLEMENT_FLOW_SPEC §7.1) ----

    /**
     * Place a hold for {@code amount} against the partner's available funds (authorize phase).
     * available = balance + credit_limit - reserved. Throws INSUFFICIENT_PREFUNDING if it would
     * exceed available. Idempotent by {@code txnRef}: a repeat reserve for a txnRef that still has an
     * active hold is a no-op that reports the existing reservation.
     */
    @Transactional
    public ReserveResult reserve(String partnerId, String txnRef, BigDecimal amount) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        BigDecimal active = activeReservation(partnerId, txnRef);
        if (active.signum() > 0) {
            // already reserved for this txnRef — idempotent no-op
            PrefundingAccount existing = toDomain(row);
            return new ReserveResult(active, existing.available(), row.getBalance());
        }
        PrefundingAccount account = toDomain(row);
        account.reserve(amount); // throws INSUFFICIENT_PREFUNDING if available < amount
        Instant now = Instant.now();
        row.setReserved(account.reserved());
        row.setUpdatedAt(now);
        balances.save(row);
        ledger.save(new LedgerEntryEntity(partnerId, txnRef, ENTRY_RESERVE, amount,
                row.getCurrency(), now));
        return new ReserveResult(amount, account.available(), row.getBalance());
    }

    /**
     * Convert the active hold for {@code txnRef} into a real debit (confirm phase): reduce balance
     * and reserved by the held amount, append a CAPTURE entry. Idempotent: if there is no active hold
     * (already captured or released) it is a no-op reporting zero captured.
     */
    @Transactional
    public CaptureResult capture(String partnerId, String txnRef) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        BigDecimal amount = activeReservation(partnerId, txnRef);
        if (amount.signum() == 0) {
            return new CaptureResult(BigDecimal.ZERO, row.getBalance());
        }
        BigDecimal previousBalance = row.getBalance();
        PrefundingAccount account = toDomain(row);
        account.capture(amount);
        Instant now = Instant.now();
        row.setBalance(account.balance());
        row.setReserved(account.reserved());
        row.setUpdatedAt(now);
        PartnerBalanceEntity saved = balances.save(row);
        ledger.save(new LedgerEntryEntity(partnerId, txnRef, ENTRY_CAPTURE, amount,
                row.getCurrency(), now));
        tierAlerts.afterBalanceChange(saved, previousBalance);
        return new CaptureResult(amount, account.balance());
    }

    /**
     * Release the active hold for {@code txnRef} without debiting (expiry / decline). Idempotent:
     * no active hold ⇒ no-op reporting zero released.
     */
    @Transactional
    public ReleaseResult release(String partnerId, String txnRef) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        BigDecimal amount = activeReservation(partnerId, txnRef);
        if (amount.signum() == 0) {
            return new ReleaseResult(BigDecimal.ZERO, row.getBalance());
        }
        PrefundingAccount account = toDomain(row);
        account.release(amount);
        Instant now = Instant.now();
        row.setReserved(account.reserved());
        row.setUpdatedAt(now);
        balances.save(row);
        ledger.save(new LedgerEntryEntity(partnerId, txnRef, ENTRY_RELEASE, amount,
                row.getCurrency(), now));
        return new ReleaseResult(amount, row.getBalance());
    }

    /**
     * Sets the partner's credit headroom — the per-partner {@code credit_limit_usd} configured in
     * config-registry, pushed here so the authorize gate can compute available = balance +
     * credit_limit - reserved without a hot-path cross-service call. Returns the new available.
     */
    @Transactional
    public CreditLimitResult setCreditLimit(String partnerId, BigDecimal creditLimit) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        row.setCreditLimit(creditLimit == null ? BigDecimal.ZERO : creditLimit);
        row.setUpdatedAt(Instant.now());
        balances.save(row);
        PrefundingAccount account = toDomain(row);
        return new CreditLimitResult(row.getCreditLimit(), account.available(), row.getBalance());
    }

    /** Outcome of setting the credit limit: the new limit, available funds, and balance. */
    public record CreditLimitResult(BigDecimal creditLimit, BigDecimal available, BigDecimal balance) {}

    /** Net active hold for a (partner, txnRef) = sum RESERVE - sum CAPTURE - sum RELEASE. */
    private BigDecimal activeReservation(String partnerId, String txnRef) {
        BigDecimal net = BigDecimal.ZERO;
        for (LedgerEntryEntity e : ledger.findByPartnerIdAndTxnRef(partnerId, txnRef)) {
            switch (e.getEntryType()) {
                case ENTRY_RESERVE -> net = net.add(e.getAmount());
                case ENTRY_CAPTURE, ENTRY_RELEASE -> net = net.subtract(e.getAmount());
                default -> { /* DEBIT/CREDIT are not reservations */ }
            }
        }
        return net;
    }

    /** Outcome of a reserve: the amount held, the resulting available funds, and the balance. */
    public record ReserveResult(BigDecimal reservedAmount, BigDecimal available, BigDecimal balance) {}

    /** Outcome of a capture: the amount captured (debited) and the balance after. */
    public record CaptureResult(BigDecimal capturedAmount, BigDecimal balanceAfter) {}

    /** Outcome of a release: the amount released and the (unchanged) balance. */
    public record ReleaseResult(BigDecimal releasedAmount, BigDecimal balance) {}
}
