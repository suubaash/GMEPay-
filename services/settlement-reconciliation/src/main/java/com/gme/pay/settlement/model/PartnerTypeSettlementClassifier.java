package com.gme.pay.settlement.model;

import org.springframework.stereotype.Component;

/**
 * Classifies settlement type purely by {@link PartnerType} — no hardcoded partner names.
 * LOCAL  -> NET, OVERSEAS -> GROSS.
 */
@Component
public class PartnerTypeSettlementClassifier implements SettlementClassifier {

    @Override
    public SettlementType classify(Partner partner) {
        if (partner == null) {
            throw new IllegalArgumentException("Partner must not be null");
        }
        PartnerType type = partner.type();
        if (type == null) {
            throw new IllegalArgumentException("Partner type must not be null for partner id=" + partner.id());
        }
        return switch (type) {
            case LOCAL -> SettlementType.NET;
            case OVERSEAS -> SettlementType.GROSS;
        };
    }
}
