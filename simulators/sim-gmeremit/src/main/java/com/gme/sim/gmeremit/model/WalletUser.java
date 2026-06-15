package com.gme.sim.gmeremit.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory wallet user with a KRW balance and transaction history.
 */
public class WalletUser {

    private final String userId;
    private final String name;
    private BigDecimal balanceKrw;
    private final List<WalletTransaction> transactions = new ArrayList<>();

    public WalletUser(String userId, String name, BigDecimal balanceKrw) {
        this.userId     = userId;
        this.name       = name;
        this.balanceKrw = balanceKrw;
    }

    public String getUserId()          { return userId; }
    public String getName()            { return name; }
    public BigDecimal getBalanceKrw()  { return balanceKrw; }
    public List<WalletTransaction> getTransactions() { return List.copyOf(transactions); }

    /** Debit and record. Called only after hub confirms APPROVED. */
    public synchronized void debit(BigDecimal charged, WalletTransaction txn) {
        balanceKrw = balanceKrw.subtract(charged);
        transactions.add(txn);
    }
}
