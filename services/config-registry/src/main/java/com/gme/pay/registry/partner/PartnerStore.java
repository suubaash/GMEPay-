package com.gme.pay.registry.partner;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory partner registry (Phase 1). config-registry owns the partner config DB; this is the
 * source of truth for {@code settlement_rounding_mode}, exposed to other services via the API only.
 */
@Component
public class PartnerStore {

    private final Map<String, Partner> partners = new ConcurrentHashMap<>();

    public PartnerStore() {
        save(new Partner("GMEREMIT", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP));
        save(new Partner("SENDMN", PartnerType.OVERSEAS, "USD", RoundingMode.DOWN));
    }

    public Partner save(Partner p) {
        partners.put(p.partnerId(), p);
        return p;
    }

    public Partner get(String partnerId) {
        Partner p = partners.get(partnerId);
        if (p == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "unknown partner: " + partnerId);
        }
        return p;
    }

    /** Update a partner's settlement rounding mode (audit-logged in the real impl). */
    public Partner updateRoundingMode(String partnerId, RoundingMode mode) {
        Partner cur = get(partnerId);
        return save(new Partner(cur.partnerId(), cur.type(), cur.settlementCurrency(), mode));
    }
}
