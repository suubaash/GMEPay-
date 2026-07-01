package com.gme.pay.prefunding.service;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.PrefundingAccount;
import com.gme.pay.prefunding.alert.TierAlertEvaluator;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerEntity;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerRepository;
import com.gme.pay.prefunding.persistence.LedgerEntryEntity;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    static final String ENTRY_CUM_CHARGE = "CUM_CHARGE";
    static final String ENTRY_CUM_REVERSE = "CUM_REVERSE";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAILY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTHLY_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter ANNUAL_FMT = DateTimeFormatter.ofPattern("yyyy");

    private final PartnerBalanceRepository balances;
    private final LedgerEntryRepository ledger;
    private final CumulativeUsageLedgerRepository cumulativeLedger;
    private final TierAlertEvaluator tierAlerts;

    public PrefundingService(PartnerBalanceRepository balances, LedgerEntryRepository ledger,
                             CumulativeUsageLedgerRepository cumulativeLedger,
                             TierAlertEvaluator tierAlerts) {
        this.balances = balances;
        this.ledger = ledger;
        this.cumulativeLedger = cumulativeLedger;
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
        return deductIdempotent(partnerId, txnRef, amount).balanceAfter();
    }

    /**
     * Idempotent atomic deduction for the internal (transaction-mgmt) channel. Identical to
     * {@link #deduct(String, String, BigDecimal)} but reports both the resulting balance AND the id of
     * the DEBIT ledger entry, and is a safe replay: under the per-partner row lock, if a DEBIT for this
     * {@code idempotencyKey} (recorded in {@code txn_ref}) already exists, NO new debit is applied — the
     * original entry's id + the current balance are returned and {@code replayed=true}. This lets
     * transaction-mgmt drive PENDING_DEBIT→DEBITED at-least-once without double-charging. Throws
     * {@link ErrorCode#INSUFFICIENT_PREFUNDING} on a fresh deduction that would overdraw.
     *
     * @param idempotencyKey the caller's idempotency key; persisted as the ledger {@code txn_ref}.
     */
    @Transactional
    public DeductResult deductIdempotent(String partnerId, String idempotencyKey, BigDecimal amount) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            LedgerEntryEntity existing = ledger.findByPartnerIdAndTxnRef(partnerId, idempotencyKey).stream()
                    .filter(e -> ENTRY_DEBIT.equals(e.getEntryType()))
                    .findFirst().orElse(null);
            if (existing != null) {
                // Replay: the deduction already happened under this key — do NOT debit again.
                return new DeductResult(row.getBalance(), existing.getId(), true);
            }
        }
        BigDecimal previousBalance = row.getBalance();
        PrefundingAccount account = toDomain(row);
        BigDecimal newBalance = account.deduct(amount); // throws INSUFFICIENT_PREFUNDING if too low
        Instant now = Instant.now();
        row.setBalance(newBalance);
        row.setUpdatedAt(now);
        PartnerBalanceEntity saved = balances.save(row);
        LedgerEntryEntity entry = ledger.save(new LedgerEntryEntity(partnerId, idempotencyKey,
                ENTRY_DEBIT, amount, row.getCurrency(), now));
        tierAlerts.afterBalanceChange(saved, previousBalance);
        return new DeductResult(newBalance, entry.getId(), false);
    }

    /** Outcome of an idempotent deduct: the balance after, the DEBIT ledger entry id, and whether this was a replay. */
    public record DeductResult(BigDecimal balanceAfter, Long ledgerEntryId, boolean replayed) {}

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
            // On a replay, surface the existing reversal CREDIT entry id (null if there was nothing to reverse).
            Long existingReversalId = entries.stream()
                    .filter(e -> ENTRY_CREDIT.equals(e.getEntryType()))
                    .map(LedgerEntryEntity::getId)
                    .findFirst().orElse(null);
            return new ReverseResult(BigDecimal.ZERO, row.getBalance(), existingReversalId);
        }
        BigDecimal previousBalance = row.getBalance();
        PrefundingAccount account = toDomain(row);
        BigDecimal newBalance = account.credit(debited);
        Instant now = Instant.now();
        row.setBalance(newBalance);
        row.setUpdatedAt(now);
        PartnerBalanceEntity saved = balances.save(row);
        LedgerEntryEntity entry = ledger.save(new LedgerEntryEntity(partnerId, txnRef, ENTRY_CREDIT,
                debited, row.getCurrency(), now));
        tierAlerts.afterBalanceChange(saved, previousBalance);
        return new ReverseResult(debited, newBalance, entry.getId());
    }

    /** Outcome of a reverse: the amount credited back, the balance after, and the reversal CREDIT ledger entry id. */
    public record ReverseResult(BigDecimal reversedAmount, BigDecimal balanceAfter, Long ledgerEntryId) {}

    // ---- cumulative usage caps (AML daily/monthly/annual, V006) ----

    /**
     * Charge {@code amountUsd} against the partner's CUMULATIVE usage for the current KST day/month/year
     * (authorize phase), rejecting with {@link ErrorCode#CUMULATIVE_LIMIT_EXCEEDED} if it would breach any
     * non-null cap. <b>Race-free:</b> the per-partner row lock is taken FIRST (the same lock that serialises
     * reserve/capture/release), then the period sums are read, compared, and the CUM_CHARGE appended — all
     * under that exclusive lock, so two concurrent authorizes for one partner cannot both pass. Idempotent by
     * {@code txnRef}: a repeat for a txnRef that already has a CUM_CHARGE is a no-op reporting current usage.
     */
    @Transactional
    public CumulativeChargeResult chargeCumulative(String partnerId, String txnRef, BigDecimal amountUsd,
                                                   BigDecimal dailyCap, BigDecimal monthlyCap, BigDecimal annualCap,
                                                   Integer dailyTxnCountLimit) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);   // serialise with reserve/capture/release + concurrent cumulative charges
        // Fall back to the caps pushed from config-registry (V007) when the caller does not supply them
        // per-request; a non-null per-request cap still overrides the stored one (additive, no behaviour change).
        if (dailyCap == null) {
            dailyCap = row.getAmlDailyCapUsd();
        }
        if (monthlyCap == null) {
            monthlyCap = row.getAmlMonthlyCapUsd();
        }
        if (annualCap == null) {
            annualCap = row.getAmlAnnualCapUsd();
        }
        if (dailyTxnCountLimit == null) {
            dailyTxnCountLimit = row.getAmlDailyTxnCountCap();
        }
        Instant now = Instant.now();
        String dKey = DAILY_FMT.format(now.atZone(KST));
        String mKey = MONTHLY_FMT.format(now.atZone(KST));
        String yKey = ANNUAL_FMT.format(now.atZone(KST));

        boolean alreadyCharged = cumulativeLedger.findByPartnerIdAndTxnRef(partnerId, txnRef).stream()
                .anyMatch(e -> ENTRY_CUM_CHARGE.equals(e.getEntryType()));
        if (alreadyCharged) {
            return new CumulativeChargeResult(
                    cumulativeLedger.sumDaily(partnerId, dKey),
                    cumulativeLedger.sumMonthly(partnerId, mKey),
                    cumulativeLedger.sumAnnual(partnerId, yKey));
        }

        BigDecimal amt = amountUsd == null ? BigDecimal.ZERO : amountUsd;
        BigDecimal daily = cumulativeLedger.sumDaily(partnerId, dKey);
        BigDecimal monthly = cumulativeLedger.sumMonthly(partnerId, mKey);
        BigDecimal annual = cumulativeLedger.sumAnnual(partnerId, yKey);
        breachIfOver(partnerId, "daily", daily, amt, dailyCap);
        breachIfOver(partnerId, "monthly", monthly, amt, monthlyCap);
        breachIfOver(partnerId, "annual", annual, amt, annualCap);

        // Velocity (V034 / WBS 13.8): this txn would be the (netDailyCount + 1)-th today.
        if (dailyTxnCountLimit != null) {
            long nextCount = cumulativeLedger.netDailyCount(partnerId, dKey) + 1;
            if (nextCount > dailyTxnCountLimit) {
                throw new ApiException(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED,
                        "partner " + partnerId + " daily transaction count " + nextCount
                                + " would exceed the velocity cap of " + dailyTxnCountLimit);
            }
        }

        cumulativeLedger.save(new CumulativeUsageLedgerEntity(
                partnerId, txnRef, ENTRY_CUM_CHARGE, amt, dKey, mKey, yKey, now));
        return new CumulativeChargeResult(daily.add(amt), monthly.add(amt), annual.add(amt));
    }

    /** Back-compat overload (amount caps only, no velocity cap). */
    @Transactional
    public CumulativeChargeResult chargeCumulative(String partnerId, String txnRef, BigDecimal amountUsd,
                                                   BigDecimal dailyCap, BigDecimal monthlyCap, BigDecimal annualCap) {
        return chargeCumulative(partnerId, txnRef, amountUsd, dailyCap, monthlyCap, annualCap, null);
    }

    private static void breachIfOver(String partnerId, String period, BigDecimal used,
                                     BigDecimal amt, BigDecimal cap) {
        if (cap != null && used.add(amt).compareTo(cap) > 0) {
            throw new ApiException(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED,
                    "partner " + partnerId + " " + period + " cumulative usage "
                            + used.add(amt) + " would exceed cap " + cap + " USD");
        }
    }

    /**
     * Reverse a previously-charged cumulative usage for {@code txnRef} (void / decline / expiry), so a
     * held-but-never-confirmed authorize does not permanently consume cap. Appends a signed-negative
     * CUM_REVERSE carrying the charge's ORIGINAL period keys (so it nets into the right period regardless of
     * when the reverse happens). Idempotent: a repeat, or a txnRef with no charge, is a no-op.
     */
    @Transactional
    public CumulativeReverseResult reverseCumulative(String partnerId, String txnRef) {
        lockOrThrow(partnerId);
        List<CumulativeUsageLedgerEntity> entries = cumulativeLedger.findByPartnerIdAndTxnRef(partnerId, txnRef);
        boolean alreadyReversed = entries.stream().anyMatch(e -> ENTRY_CUM_REVERSE.equals(e.getEntryType()));
        if (alreadyReversed) {
            return new CumulativeReverseResult(BigDecimal.ZERO);
        }
        CumulativeUsageLedgerEntity charge = entries.stream()
                .filter(e -> ENTRY_CUM_CHARGE.equals(e.getEntryType()))
                .findFirst().orElse(null);
        if (charge == null) {
            return new CumulativeReverseResult(BigDecimal.ZERO);
        }
        cumulativeLedger.save(new CumulativeUsageLedgerEntity(
                partnerId, txnRef, ENTRY_CUM_REVERSE, charge.getAmountUsd().negate(),
                charge.getDailyKey(), charge.getMonthlyKey(), charge.getAnnualKey(), Instant.now()));
        return new CumulativeReverseResult(charge.getAmountUsd());
    }

    /** Post-charge cumulative usage figures for the txn's KST day/month/year. */
    public record CumulativeChargeResult(BigDecimal dailyUsage, BigDecimal monthlyUsage, BigDecimal annualUsage) {}

    /** Outcome of a cumulative reverse: the amount returned to the period (0 if nothing / already reversed). */
    public record CumulativeReverseResult(BigDecimal reversedAmount) {}

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

    /**
     * Idempotent upsert of the per-partner credit limit AND AML caps pushed from config-registry
     * (IR-pf-2): {@code credit_limit_usd} plus the daily/monthly/annual amount caps and the daily
     * transaction-count cap. These are STORED on the partner row so the deduct/reserve gate
     * (available = balance + credit_limit − reserved) and the AML cap gate read them without a
     * per-request argument. Re-pushing simply overwrites with the latest config.
     *
     * <p>Upsert semantics: if the partner has no balance row yet (config push can arrive before
     * provisioning), one is created with a zero opening balance so the limits are not lost; a later
     * {@code /provision} for an existing row is still rejected by its own 409 guard. A {@code null}
     * cap means "no cap for that period"; a {@code null} {@code creditLimitUsd} resets the headroom to 0.
     */
    @Transactional
    public PartnerLimits pushPartnerLimits(String partnerId, BigDecimal creditLimitUsd,
                                           BigDecimal amlDailyCapUsd, BigDecimal amlMonthlyCapUsd,
                                           BigDecimal amlAnnualCapUsd, Integer amlDailyTxnCountCap) {
        PartnerBalanceEntity row = balances.lockByPartnerId(partnerId).orElse(null);
        if (row == null) {
            row = new PartnerBalanceEntity(partnerId, "USD", BigDecimal.ZERO, null, Instant.now());
        }
        row.setCreditLimit(creditLimitUsd == null ? BigDecimal.ZERO : creditLimitUsd);
        row.setAmlDailyCapUsd(amlDailyCapUsd);
        row.setAmlMonthlyCapUsd(amlMonthlyCapUsd);
        row.setAmlAnnualCapUsd(amlAnnualCapUsd);
        row.setAmlDailyTxnCountCap(amlDailyTxnCountCap);
        row.setUpdatedAt(Instant.now());
        balances.save(row);
        PrefundingAccount account = toDomain(row);
        return new PartnerLimits(row.getCreditLimit(), row.getAmlDailyCapUsd(), row.getAmlMonthlyCapUsd(),
                row.getAmlAnnualCapUsd(), row.getAmlDailyTxnCountCap(), account.available(), row.getBalance());
    }

    /** Reads the stored per-partner credit limit + AML caps. Throws if the partner is unknown. */
    @Transactional(readOnly = true)
    public PartnerLimits getPartnerLimits(String partnerId) {
        PartnerBalanceEntity row = balances.findById(partnerId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "unknown partnerId " + partnerId));
        PrefundingAccount account = toDomain(row);
        return new PartnerLimits(row.getCreditLimit(), row.getAmlDailyCapUsd(), row.getAmlMonthlyCapUsd(),
                row.getAmlAnnualCapUsd(), row.getAmlDailyTxnCountCap(), account.available(), row.getBalance());
    }

    /** Stored per-partner limits (credit headroom + AML caps) plus derived available + balance. */
    public record PartnerLimits(BigDecimal creditLimitUsd, BigDecimal amlDailyCapUsd,
                                BigDecimal amlMonthlyCapUsd, BigDecimal amlAnnualCapUsd,
                                Integer amlDailyTxnCountCap, BigDecimal available, BigDecimal balance) {}

    // ---- CPM reserve/release (overseas QR token issuance, Phase-2 cross-service wiring) ----

    /**
     * Soft-hold {@code amount} of available funds at OVERSEAS CPM token issuance, idempotent on
     * {@code idempotencyKey} (qr-service's CPM token / session id, persisted as the ledger {@code txn_ref}).
     * Identical lock + invariant to {@link #reserve(String, String, BigDecimal)} — available = balance +
     * credit_limit − reserved, throws {@link ErrorCode#INSUFFICIENT_PREFUNDING} if the hold would overdraw —
     * but additionally surfaces the RESERVE ledger entry id as the {@code reservationId} handle the matching
     * {@link #releaseForCpm(String, String)} (or a hard capture) references, plus the partner's total
     * reserved. A replay for a key that still has an active hold is a no-op reporting the existing reservation.
     *
     * @param idempotencyKey the reserve key (also the release key); persisted as the ledger {@code txn_ref}.
     */
    @Transactional
    public CpmReserveResult reserveForCpm(String partnerId, String idempotencyKey, BigDecimal amount) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        BigDecimal active = activeReservation(partnerId, idempotencyKey);
        if (active.signum() > 0) {
            // Idempotent replay: surface the existing RESERVE entry id + current balances, no new hold.
            Long existingId = ledger.findByPartnerIdAndTxnRef(partnerId, idempotencyKey).stream()
                    .filter(e -> ENTRY_RESERVE.equals(e.getEntryType()))
                    .map(LedgerEntryEntity::getId)
                    .findFirst().orElse(null);
            PrefundingAccount existing = toDomain(row);
            return new CpmReserveResult(existingId, active, existing.available(),
                    row.getReserved(), true);
        }
        PrefundingAccount account = toDomain(row);
        account.reserve(amount); // throws INSUFFICIENT_PREFUNDING if available < amount
        Instant now = Instant.now();
        row.setReserved(account.reserved());
        row.setUpdatedAt(now);
        balances.save(row);
        LedgerEntryEntity entry = ledger.save(new LedgerEntryEntity(partnerId, idempotencyKey,
                ENTRY_RESERVE, amount, row.getCurrency(), now));
        return new CpmReserveResult(entry.getId(), amount, account.available(),
                account.reserved(), false);
    }

    /**
     * Free the active CPM hold for {@code idempotencyKey} on token expiry / decline — idempotent: no active
     * hold (already released or captured) ⇒ a 0 no-op. Delegates to the same RELEASE-ledger machinery as
     * {@link #release(String, String)} and additionally reports the partner's total reserved after release.
     */
    @Transactional
    public CpmReleaseResult releaseForCpm(String partnerId, String idempotencyKey) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        BigDecimal amount = activeReservation(partnerId, idempotencyKey);
        if (amount.signum() == 0) {
            return new CpmReleaseResult(BigDecimal.ZERO, row.getBalance(), row.getReserved());
        }
        PrefundingAccount account = toDomain(row);
        account.release(amount);
        Instant now = Instant.now();
        row.setReserved(account.reserved());
        row.setUpdatedAt(now);
        balances.save(row);
        ledger.save(new LedgerEntryEntity(partnerId, idempotencyKey, ENTRY_RELEASE, amount,
                row.getCurrency(), now));
        return new CpmReleaseResult(amount, row.getBalance(), account.reserved());
    }

    /** Outcome of a CPM reserve: the reservation (RESERVE ledger) id, amount held, available + total reserved after, replay flag. */
    public record CpmReserveResult(Long reservationId, BigDecimal reservedAmount, BigDecimal available,
                                   BigDecimal reservedTotal, boolean replayed) {}

    /** Outcome of a CPM release: the amount released, the (unchanged) balance, and total reserved after. */
    public record CpmReleaseResult(BigDecimal releasedAmount, BigDecimal balance, BigDecimal reservedTotal) {}

    // ---- deduction history (IR-pe-2: payment-executor balance ?include_history=true) ----

    /**
     * Most-recent-first DEBIT history for a partner, capped at {@code limit} (clamped to 1..500; a non-positive
     * limit falls back to 20). Read-only — no lock. Each row maps to a {@code (amountUsd, at, txnRef)} tuple
     * mirroring the canonical {@code BalanceDeductionEntry} shape (the controller adapts to the lib DTO).
     */
    @Transactional(readOnly = true)
    public List<DeductionHistoryRow> recentDeductions(String partnerId, int limit) {
        int capped = limit <= 0 ? 20 : Math.min(limit, 500);
        return ledger.findByPartnerIdAndEntryTypeOrderByCreatedAtDescIdDesc(
                        partnerId, ENTRY_DEBIT, org.springframework.data.domain.PageRequest.of(0, capped))
                .stream()
                .map(e -> new DeductionHistoryRow(e.getAmount(), e.getCreatedAt(), e.getTxnRef()))
                .toList();
    }

    /** One deduction-history row: USD amount, the instant applied, and the originating txnRef. */
    public record DeductionHistoryRow(BigDecimal amountUsd, Instant at, String txnRef) {}

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
