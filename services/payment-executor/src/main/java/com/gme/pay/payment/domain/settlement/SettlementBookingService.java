package com.gme.pay.payment.domain.settlement;

import com.gme.pay.money.BookedAmount;
import com.gme.pay.money.SettlementRounding;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Books a partner's settlement liability under that partner's configured rounding rule
 * (see RATE-04 / MONEY_CONVENTION.md). The residual is carried for posting to REVENUE_ROUNDING.
 */
public class SettlementBookingService {

    private final PartnerConfigClient partnerConfigClient;

    public SettlementBookingService(PartnerConfigClient partnerConfigClient) {
        this.partnerConfigClient = Objects.requireNonNull(partnerConfigClient, "partnerConfigClient");
    }

    public SettlementBooking book(long partnerId, BigDecimal preciseSettlementAmount, String currency) {
        PartnerConfigClient.SettlementConfig cfg = partnerConfigClient.getSettlementConfig(partnerId);
        BookedAmount b = SettlementRounding.book(preciseSettlementAmount, currency, cfg.settlementRoundingMode());
        return new SettlementBooking(b.booked(), b.residual(), cfg.settlementRoundingMode(), currency);
    }
}
