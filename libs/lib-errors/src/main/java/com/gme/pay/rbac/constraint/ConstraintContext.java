package com.gme.pay.rbac.constraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The request-time facts a {@link ConstraintEngine} evaluates against: when the request
 * happens, where the caller is, the money involved, and approval state. Built per request
 * from the stamped {@code X-Gme-*} attributes + request params/body. Any field may be null
 * (a constraint that needs a field absent from the context passes — the dimension simply
 * isn't being exercised by this request).
 *
 * @param now             request instant (UTC)
 * @param country         caller country (ISO-3166 alpha-2)
 * @param region          caller region (e.g. JAPAN, KOREA, CIS)
 * @param office          caller office id
 * @param amount          monetary amount in the request (for AMOUNT / DATA_FILTER)
 * @param currency        currency of {@code amount} (and DATA_FILTER currency scoping)
 * @param merchantId      merchant the request concerns (DATA_FILTER)
 * @param requestedDate   business date the request concerns (DATA_FILTER date-range)
 * @param superuser       CFO/break-glass override — bypasses ALL constraints
 * @param approvalGranted a prior approval exists (satisfies an APPROVAL constraint)
 */
public record ConstraintContext(
        Instant now,
        String country,
        String region,
        String office,
        BigDecimal amount,
        String currency,
        String merchantId,
        LocalDate requestedDate,
        boolean superuser,
        boolean approvalGranted) {

    public static Builder builder(Instant now) {
        return new Builder(now);
    }

    public static final class Builder {
        private final Instant now;
        private String country, region, office, currency, merchantId;
        private BigDecimal amount;
        private LocalDate requestedDate;
        private boolean superuser, approvalGranted;

        private Builder(Instant now) { this.now = now; }

        public Builder country(String v) { this.country = v; return this; }
        public Builder region(String v) { this.region = v; return this; }
        public Builder office(String v) { this.office = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder merchantId(String v) { this.merchantId = v; return this; }
        public Builder requestedDate(LocalDate v) { this.requestedDate = v; return this; }
        public Builder superuser(boolean v) { this.superuser = v; return this; }
        public Builder approvalGranted(boolean v) { this.approvalGranted = v; return this; }

        public ConstraintContext build() {
            return new ConstraintContext(now, country, region, office, amount, currency,
                    merchantId, requestedDate, superuser, approvalGranted);
        }
    }
}
