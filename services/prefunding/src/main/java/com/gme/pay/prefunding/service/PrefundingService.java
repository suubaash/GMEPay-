package com.gme.pay.prefunding.service;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.PrefundingAccount;
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
 */
@Service
public class PrefundingService {

    static final String ENTRY_DEBIT = "DEBIT";
    static final String ENTRY_CREDIT = "CREDIT";

    private final PartnerBalanceRepository balances;
    private final LedgerEntryRepository ledger;

    public PrefundingService(PartnerBalanceRepository balances, LedgerEntryRepository ledger) {
        this.balances = balances;
        this.ledger = ledger;
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
        PrefundingAccount account = toDomain(row);
        BigDecimal newBalance = account.deduct(amount); // throws INSUFFICIENT_PREFUNDING if too low
        Instant now = Instant.now();
        row.setBalance(newBalance);
        row.setUpdatedAt(now);
        balances.save(row);
        ledger.save(new LedgerEntryEntity(partnerId, txnRef, ENTRY_DEBIT, amount,
                row.getCurrency(), now));
        return newBalance;
    }

    /** Atomically credit {@code amount} onto the partner's balance and append a CREDIT entry. */
    @Transactional
    public BigDecimal credit(String partnerId, BigDecimal amount) {
        PartnerBalanceEntity row = lockOrThrow(partnerId);
        PrefundingAccount account = toDomain(row);
        BigDecimal newBalance = account.credit(amount);
        Instant now = Instant.now();
        row.setBalance(newBalance);
        row.setUpdatedAt(now);
        balances.save(row);
        ledger.save(new LedgerEntryEntity(partnerId, null, ENTRY_CREDIT, amount,
                row.getCurrency(), now));
        return newBalance;
    }

    private PartnerBalanceEntity lockOrThrow(String partnerId) {
        return balances.lockByPartnerId(partnerId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "unknown partnerId " + partnerId));
    }

    private static PrefundingAccount toDomain(PartnerBalanceEntity row) {
        return new PrefundingAccount(row.getPartnerId(), row.getCurrency(),
                row.getBalance(), row.getLowBalanceThreshold());
    }
}
