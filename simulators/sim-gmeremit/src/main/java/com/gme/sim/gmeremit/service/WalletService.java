package com.gme.sim.gmeremit.service;

import com.gme.sim.gmeremit.model.WalletStore;
import com.gme.sim.gmeremit.model.WalletTransaction;
import com.gme.sim.gmeremit.model.WalletUser;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Core wallet business logic.
 *
 * <p>Insufficient-funds check is done locally (balance &lt; amount + 500)
 * without calling the hub, as specified.
 */
@Service
public class WalletService {

    private static final BigDecimal FEE = new BigDecimal("500");

    private final WalletStore store;
    private final HubClient   hub;

    public WalletService(WalletStore store, HubClient hub) {
        this.store = store;
        this.hub   = hub;
    }

    public PayResult pay(String userId, String qrPayload, String amountKrw) {
        WalletUser user = store.findUser(userId).orElse(null);
        if (user == null) {
            return PayResult.declined("USER_NOT_FOUND", null, null);
        }

        BigDecimal amount = new BigDecimal(amountKrw);
        BigDecimal required = amount.add(FEE);

        // Local pre-check — do not call hub if balance is insufficient
        if (user.getBalanceKrw().compareTo(required) < 0) {
            return PayResult.declined("INSUFFICIENT_FUNDS", user.getBalanceKrw().toPlainString(), null);
        }

        // Call the hub
        HubClient.HubPayResult result = hub.pay(qrPayload, amountKrw, userId);

        if (result.isHubDown()) {
            return PayResult.declined("HUB_UNAVAILABLE", user.getBalanceKrw().toPlainString(), null);
        }

        if (!result.approved()) {
            return PayResult.declined(result.declineReason(), user.getBalanceKrw().toPlainString(), result.merchantName());
        }

        // Debit balance by chargedKrw returned by hub
        BigDecimal charged = new BigDecimal(result.chargedKrw());
        WalletTransaction txn = new WalletTransaction(
                result.schemeTxnRef(),
                result.merchantName(),
                result.payAmountKrw(),
                result.feeKrw(),
                result.chargedKrw(),
                result.committedAt()
        );
        user.debit(charged, txn);

        return PayResult.approved(
                result.schemeTxnRef(),
                result.merchantName(),
                result.payAmountKrw(),
                result.feeKrw(),
                result.chargedKrw(),
                result.committedAt(),
                user.getBalanceKrw().toPlainString()
        );
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    public record PayResult(
            boolean approved,
            String schemeTxnRef,
            String merchantName,
            String payAmountKrw,
            String feeKrw,
            String chargedKrw,
            String committedAt,
            String newBalanceKrw,
            String declineReason
    ) {
        static PayResult approved(String schemeTxnRef, String merchantName,
                                  String payAmountKrw, String feeKrw, String chargedKrw,
                                  String committedAt, String newBalance) {
            return new PayResult(true, schemeTxnRef, merchantName,
                    payAmountKrw, feeKrw, chargedKrw, committedAt, newBalance, null);
        }

        static PayResult declined(String reason, String currentBalance, String merchantName) {
            return new PayResult(false, null, merchantName,
                    null, null, null, null, currentBalance, reason);
        }
    }
}
