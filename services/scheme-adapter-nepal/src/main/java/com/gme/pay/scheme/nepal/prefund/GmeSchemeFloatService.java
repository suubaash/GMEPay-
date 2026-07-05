package com.gme.pay.scheme.nepal.prefund;

import com.gme.pay.scheme.nepal.persistence.GmeSchemeBalanceEntity;
import com.gme.pay.scheme.nepal.persistence.GmeSchemeBalanceEntryEntity;
import com.gme.pay.scheme.nepal.persistence.GmeSchemeBalanceEntryRepository;
import com.gme.pay.scheme.nepal.persistence.GmeSchemeBalanceRepository;
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
 * GME's prepaid float held WITH the Nepal QR scheme — a REAL decrementing ledger (SETTLEMENT_FLOW_SPEC
 * §7.2). Before this existed, the payment-executor's Nepal {@code checkBalance} was a no-op that always
 * allowed the payout. Now the float is seeded from an opening balance, credited on top-up, and debited
 * on every committed payout; the pre-submit {@link #check} reads the live running balance so a payout is
 * declined at AUTHORIZE once the float is short.
 *
 * <p>Debits and credits are idempotent on {@code txnRef}. NPR is held to two decimals (paisa);
 * {@link #debitPaisa} converts the adapter's paisa payout to NPR.
 */
@Service
public class GmeSchemeFloatService {

    private static final Logger log = LoggerFactory.getLogger(GmeSchemeFloatService.class);
    private static final BigDecimal PAISA_PER_NPR = BigDecimal.valueOf(100);

    private final GmeSchemeBalanceRepository balanceRepo;
    private final GmeSchemeBalanceEntryRepository entryRepo;
    private final String schemeCode;
    private final String currency;
    private final BigDecimal openingBalance;

    public GmeSchemeFloatService(
            GmeSchemeBalanceRepository balanceRepo,
            GmeSchemeBalanceEntryRepository entryRepo,
            @Value("${gmepay.scheme.nepal.scheme-code:NEPAL}") String schemeCode,
            @Value("${gmepay.scheme.nepal.payout-currency:NPR}") String currency,
            @Value("${gmepay.scheme.nepal.opening-prepaid-balance-npr:100000000}") long openingBalanceNpr) {
        this.balanceRepo = balanceRepo;
        this.entryRepo = entryRepo;
        this.schemeCode = schemeCode;
        this.currency = currency;
        this.openingBalance = BigDecimal.valueOf(openingBalanceNpr).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** NPR to two decimals, null-safe. */
    private static BigDecimal npr(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Pre-submit balance inquiry: may GME fund {@code amountNpr} from its Nepal float right now?
     * Read-only — the actual decrement happens at {@link #debitPaisa} on the committed payout.
     */
    @Transactional
    public BalanceCheck check(BigDecimal amountNpr) {
        BigDecimal available = ensureSeeded().getBalance();
        boolean allowed = available.compareTo(npr(amountNpr)) >= 0;
        return new BalanceCheck(allowed, available);
    }

    /** Debit a committed payout given in paisa (adapter's native unit); converts to NPR. Idempotent. */
    @Transactional
    public BigDecimal debitPaisa(String txnRef, long amountPaisa) {
        BigDecimal npr = BigDecimal.valueOf(amountPaisa).divide(PAISA_PER_NPR, 2, RoundingMode.HALF_UP);
        return apply(GmeSchemeBalanceEntryEntity.TYPE_DEBIT, txnRef, npr);
    }

    /** Credit a top-up given in NPR, keyed idempotently on {@code txnRef}. Replays are a no-op. */
    @Transactional
    public BigDecimal credit(String txnRef, BigDecimal amountNpr) {
        return apply(GmeSchemeBalanceEntryEntity.TYPE_CREDIT, txnRef, npr(amountNpr));
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
            log.warn("Nepal float for scheme {} went NEGATIVE ({}) after {} {} of {} — payout already "
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
                log.info("seeded Nepal GME prepaid float: scheme={} opening={} {}",
                        schemeCode, openingBalance, currency);
                return seeded;
            } catch (DataIntegrityViolationException race) {
                return balanceRepo.findById(schemeCode).orElseThrow(() -> race);
            }
        });
    }

    /** Result of a pre-submit float inquiry. */
    public record BalanceCheck(boolean allowed, BigDecimal available) {
    }
}
