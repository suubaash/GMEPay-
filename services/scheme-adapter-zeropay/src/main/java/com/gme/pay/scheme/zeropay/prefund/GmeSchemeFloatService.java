package com.gme.pay.scheme.zeropay.prefund;

import com.gme.pay.scheme.zeropay.persistence.GmeSchemeBalanceEntity;
import com.gme.pay.scheme.zeropay.persistence.GmeSchemeBalanceEntryEntity;
import com.gme.pay.scheme.zeropay.persistence.GmeSchemeBalanceEntryRepository;
import com.gme.pay.scheme.zeropay.persistence.GmeSchemeBalanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * GME's prepaid float held WITH the ZeroPay scheme — a REAL decrementing ledger (SETTLEMENT_FLOW_SPEC
 * §7.2). Seeded once from an opening balance, credited on top-up, and debited on every committed
 * payout; the pre-submit {@link #check} reads the live running balance so a payout is declined at
 * AUTHORIZE once the float is short — not only when a single payout exceeds a fixed number.
 *
 * <p>Debits and credits are idempotent on {@code txnRef} (the {@code gme_scheme_balance_entry} unique
 * key), so a retried payout or top-up never double-applies. KRW is whole won (scale 0).
 */
@Service
public class GmeSchemeFloatService {

    private static final Logger log = LoggerFactory.getLogger(GmeSchemeFloatService.class);

    private final GmeSchemeBalanceRepository balanceRepo;
    private final GmeSchemeBalanceEntryRepository entryRepo;
    private final String schemeCode;
    private final String currency;
    private final BigDecimal openingBalance;

    public GmeSchemeFloatService(
            GmeSchemeBalanceRepository balanceRepo,
            GmeSchemeBalanceEntryRepository entryRepo,
            @Value("${adapter.zeropay.scheme-id:ZEROPAY}") String schemeCode,
            @Value("${adapter.zeropay.payout-currency:KRW}") String currency,
            @Value("${gmepay.scheme.zeropay.opening-prepaid-balance-krw:1000000000}") long openingBalanceKrw) {
        this.balanceRepo = balanceRepo;
        this.entryRepo = entryRepo;
        this.schemeCode = schemeCode;
        this.currency = currency;
        this.openingBalance = BigDecimal.valueOf(openingBalanceKrw).setScale(0, RoundingMode.UNNECESSARY);
    }

    /** Whole-won, null-safe. */
    private static BigDecimal krw(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Pre-submit balance inquiry: may GME fund {@code amount} from its ZeroPay float right now?
     * Read-only (no reservation) — the actual decrement happens at {@link #debit} on the committed payout.
     */
    @Transactional
    public BalanceCheck check(BigDecimal amount) {
        BigDecimal available = ensureSeeded().getBalance();
        boolean allowed = available.compareTo(krw(amount)) >= 0;
        return new BalanceCheck(allowed, available);
    }

    /**
     * Debit the float by a committed payout amount, keyed idempotently on {@code txnRef}. A replayed
     * payout (same ref) is a no-op returning the unchanged balance. Emits a warning — never an error —
     * if the balance goes negative, since the payout has already occurred at the scheme.
     */
    @Transactional
    public BigDecimal debit(String txnRef, BigDecimal amount) {
        return apply(GmeSchemeBalanceEntryEntity.TYPE_DEBIT, txnRef, krw(amount));
    }

    /** Credit the float by a top-up, keyed idempotently on {@code txnRef}. Replays are a no-op. */
    @Transactional
    public BigDecimal credit(String txnRef, BigDecimal amount) {
        return apply(GmeSchemeBalanceEntryEntity.TYPE_CREDIT, txnRef, krw(amount));
    }

    /** Current running balance (seeding on first access). */
    @Transactional
    public GmeSchemeBalanceEntity currentBalance() {
        return ensureSeeded();
    }

    /** Most-recent ledger entries for the inquiry view. */
    @Transactional(readOnly = true)
    public List<GmeSchemeBalanceEntryEntity> recentEntries() {
        return entryRepo.findTop20BySchemeCodeOrderByIdDesc(schemeCode);
    }

    public String schemeCode() {
        return schemeCode;
    }

    public String currency() {
        return currency;
    }

    // --------------------------------------------------------------------- internals

    private BigDecimal apply(String type, String txnRef, BigDecimal magnitude) {
        if (txnRef == null || txnRef.isBlank()) {
            throw new IllegalArgumentException("txnRef is required for a " + type);
        }
        if (magnitude.signum() < 0) {
            throw new IllegalArgumentException(type + " magnitude must be non-negative: " + magnitude);
        }
        ensureSeeded();
        // Idempotency: this exact (type, ref) already applied → return the current balance unchanged.
        if (entryRepo.existsBySchemeCodeAndEntryTypeAndTxnRef(schemeCode, type, txnRef)) {
            log.debug("float {} {} already applied for scheme {} — no-op", type, txnRef, schemeCode);
            return balanceRepo.findById(schemeCode).map(GmeSchemeBalanceEntity::getBalance).orElse(openingBalance);
        }
        GmeSchemeBalanceEntity bal = balanceRepo.findWithLockBySchemeCode(schemeCode)
                .orElseThrow(() -> new IllegalStateException("float row missing for scheme " + schemeCode));
        BigDecimal updated = GmeSchemeBalanceEntryEntity.TYPE_DEBIT.equals(type)
                ? bal.getBalance().subtract(magnitude)
                : bal.getBalance().add(magnitude);
        bal.setBalance(updated);
        balanceRepo.save(bal);
        entryRepo.save(new GmeSchemeBalanceEntryEntity(schemeCode, type, txnRef, magnitude, updated));
        if (updated.signum() < 0) {
            log.warn("ZeroPay float for scheme {} went NEGATIVE ({}) after {} {} of {} — payout already "
                    + "committed at scheme; top-up required", schemeCode, updated, type, txnRef, magnitude);
        }
        return updated;
    }

    /** Insert the opening balance + OPENING journal entry on first use; idempotent under a PK race. */
    private GmeSchemeBalanceEntity ensureSeeded() {
        return balanceRepo.findById(schemeCode).orElseGet(() -> {
            try {
                GmeSchemeBalanceEntity seeded =
                        balanceRepo.saveAndFlush(new GmeSchemeBalanceEntity(schemeCode, currency, openingBalance));
                entryRepo.save(new GmeSchemeBalanceEntryEntity(
                        schemeCode, GmeSchemeBalanceEntryEntity.TYPE_OPENING, "OPENING",
                        openingBalance, openingBalance));
                log.info("seeded ZeroPay GME prepaid float: scheme={} opening={} {}",
                        schemeCode, openingBalance, currency);
                return seeded;
            } catch (DataIntegrityViolationException race) {
                // Another thread seeded it first — read the winner.
                return balanceRepo.findById(schemeCode)
                        .orElseThrow(() -> race);
            }
        });
    }

    /** Result of a pre-submit float inquiry. */
    public record BalanceCheck(boolean allowed, BigDecimal available) {
    }
}
