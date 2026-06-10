package com.gme.pay.payment.domain.settlement;

import com.gme.pay.money.BookedAmount;
import com.gme.pay.money.SettlementRounding;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PartnerConfigClient.PartnerConfigView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Resolves the partner's settlement-rounding mode (via {@link PartnerConfigClient}) and books a
 * precise amount as a partner liability using {@link SettlementRounding#book(BigDecimal, String,
 * java.math.RoundingMode)}.
 *
 * <p>See {@code docs/MONEY_CONVENTION.md} – the booked liability + the chosen mode are rate-locked
 * onto the transaction, and the residual is posted to {@code REVENUE_ROUNDING} so the internal
 * ledger stays balanced while partner reconciliation remains exact.
 */
@Service
public class SettlementBookingService {

    private final PartnerConfigClient partnerConfigClient;

    public SettlementBookingService(PartnerConfigClient partnerConfigClient) {
        this.partnerConfigClient = Objects.requireNonNull(partnerConfigClient, "partnerConfigClient");
    }

    /**
     * Books {@code preciseAmount} as the partner's settlement liability under that partner's
     * configured rounding rule.
     *
     * @param partnerId      the partner whose rounding policy applies
     * @param preciseAmount  the full-precision amount to book
     * @param currency       the ISO-4217 currency of {@code preciseAmount}
     * @return the booking result (booked, residual, mode, currency)
     */
    public SettlementBooking book(long partnerId, BigDecimal preciseAmount, String currency) {
        Objects.requireNonNull(preciseAmount, "preciseAmount");
        Objects.requireNonNull(currency, "currency");
        PartnerConfigView cfg = partnerConfigClient.loadPartner(String.valueOf(partnerId));
        BookedAmount booked = SettlementRounding.book(
                preciseAmount, currency, cfg.settlementRoundingMode());
        return new SettlementBooking(booked.booked(), booked.residual(), booked.mode(), currency);
    }
}
