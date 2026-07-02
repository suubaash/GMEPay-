package com.gme.sim.gmeremit.service;

import com.gme.sim.gmeremit.model.WalletStore;
import com.gme.sim.gmeremit.model.WalletTransaction;
import com.gme.sim.gmeremit.model.WalletUser;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Core wallet business logic.
 *
 * <p>Two corridors, keyed off the scanned QR network:
 * <ul>
 *   <li><b>Domestic (ZeroPay, KRW)</b> — {@code chargedKrw = amountKrw + 500 fee}; merchant is
 *       paid in KRW. Unchanged behaviour.</li>
 *   <li><b>Nepal (Fonepay, NPR)</b> — the user enters/sees an NPR amount; the KRW debit is
 *       {@code nprAmount × effectiveKrwPerNpr + 500 fee} using a sim FX rate. The hub is called
 *       with the NPR amount and {@code currency=NPR}.</li>
 * </ul>
 *
 * <p>Insufficient-funds is checked locally (KRW balance &lt; KRW debit) before calling the hub.
 */
@Service
public class WalletService {

    private static final BigDecimal FEE = new BigDecimal("500");

    private final WalletStore store;
    private final HubClient   hub;
    private final FxRates     fx;

    public WalletService(WalletStore store, HubClient hub, FxRates fx) {
        this.store = store;
        this.hub   = hub;
        this.fx    = fx;
    }

    /**
     * @param amount the amount in the MERCHANT currency (KRW for domestic, NPR for Nepal)
     */
    public PayResult pay(String userId, String qrPayload, String amount) {
        WalletUser user = store.findUser(userId).orElse(null);
        if (user == null) {
            return PayResult.declined("USER_NOT_FOUND", null, null);
        }

        QrNetwork network = QrNetwork.detect(qrPayload);
        String currency   = network.currency();

        BigDecimal payAmount = new BigDecimal(amount);   // in merchant currency

        // KRW value of the payment leg (domestic = same; Nepal = converted at the sim FX rate).
        BigDecimal payKrw = network == QrNetwork.NEPAL
                ? fx.nprToKrw(payAmount)
                : payAmount;

        BigDecimal chargedKrw = payKrw.add(FEE);

        // Local pre-check — do not call hub if the KRW wallet cannot cover the KRW debit.
        if (user.getBalanceKrw().compareTo(chargedKrw) < 0) {
            return PayResult.declined("INSUFFICIENT_FUNDS", user.getBalanceKrw().toPlainString(), null);
        }

        // Call the hub with the amount in the merchant currency + the currency tag.
        HubClient.HubPayResult result = hub.pay(qrPayload, currency, payAmount.toPlainString(), userId);

        if (result.isHubDown()) {
            return PayResult.declined("HUB_UNAVAILABLE", user.getBalanceKrw().toPlainString(), null);
        }
        if (!result.approved()) {
            return PayResult.declined(result.declineReason(), user.getBalanceKrw().toPlainString(), result.merchantName());
        }

        // The hub is authoritative for domestic KRW figures if it returns them; otherwise (and for
        // the Nepal corridor while the hub side is being wired) fall back to the wallet's own math.
        String feeKrwOut     = result.feeKrw()     != null ? result.feeKrw()     : FEE.toPlainString();
        String chargedKrwOut  = result.chargedKrw() != null ? result.chargedKrw() : chargedKrw.toPlainString();
        String payAmountOut   = result.payAmount()  != null ? result.payAmount()  : payAmount.toPlainString();
        String payAmountKrwOut = result.payAmountKrw() != null ? result.payAmountKrw() : payKrw.toPlainString();
        String currencyOut    = result.currency()   != null ? result.currency()   : currency;

        BigDecimal debit = new BigDecimal(chargedKrwOut);
        WalletTransaction txn = new WalletTransaction(
                result.schemeTxnRef(),
                result.merchantName(),
                currencyOut,
                payAmountOut,
                feeKrwOut,
                chargedKrwOut,
                result.committedAt()
        );
        user.debit(debit, txn);

        return PayResult.approved(
                result.schemeTxnRef(),
                result.merchantName(),
                currencyOut,
                payAmountOut,
                payAmountKrwOut,
                feeKrwOut,
                chargedKrwOut,
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
            String currency,
            String payAmount,      // amount in merchant currency (KRW or NPR)
            String payAmountKrw,   // KRW value of the payment leg (for FX display)
            String feeKrw,
            String chargedKrw,     // KRW debited from the wallet (payKrw + fee)
            String committedAt,
            String newBalanceKrw,
            String declineReason
    ) {
        static PayResult approved(String schemeTxnRef, String merchantName, String currency,
                                  String payAmount, String payAmountKrw, String feeKrw,
                                  String chargedKrw, String committedAt, String newBalance) {
            return new PayResult(true, schemeTxnRef, merchantName, currency,
                    payAmount, payAmountKrw, feeKrw, chargedKrw, committedAt, newBalance, null);
        }

        static PayResult declined(String reason, String currentBalance, String merchantName) {
            return new PayResult(false, null, merchantName, null,
                    null, null, null, null, null, currentBalance, reason);
        }
    }
}
