package com.gme.pay.reporting.hometax;

import com.gme.pay.contracts.PartnerRegulatoryConfigView;
import com.gme.pay.contracts.VatTreatment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Default (stub) implementation of {@link RegulatoryConfigClient}.
 *
 * <p>Returns a safe default {@link PartnerRegulatoryConfigView} with
 * {@code vatTreatment=ZERO_RATED_EXPORT} and a statutory CTR threshold of
 * KRW 10,000,000. Active whenever no production {@link RegulatoryConfigClient}
 * bean is present.
 *
 * <p>Replace with a {@code RestRegulatoryConfigClient} calling
 * {@code GET /v1/partners/{partnerCode}/regulatory} on config-registry once
 * credentials and network access are available.
 */
@Component
@ConditionalOnMissingBean(value = RegulatoryConfigClient.class,
        ignored = StubRegulatoryConfigClient.class)
public class StubRegulatoryConfigClient implements RegulatoryConfigClient {

    /** Statutory KoFIU CTR threshold (KRW 10,000,000 per FTRA Article 4). */
    private static final BigDecimal STATUTORY_CTR_DEFAULT = new BigDecimal("10000000");

    /** Statutory Travel Rule threshold (KRW 1,000,000). */
    private static final BigDecimal STATUTORY_TRAVEL_RULE_DEFAULT = new BigDecimal("1000000");

    @Override
    public PartnerRegulatoryConfigView getRegulatory(String partnerCode) {
        // Safe defaults: zero-rated export (cross-border remittance) + statutory thresholds
        return new PartnerRegulatoryConfigView(
                null,                       // partnerId — unknown without a DB lookup
                null,                       // bokTxnCode
                null,                       // bokFxReportingCategory
                null,                       // bokRemitterType
                "stub-cert-id",             // hometaxIssuerCertId
                VatTreatment.ZERO_RATED_EXPORT,
                null,                       // kofiuEntityId
                STATUTORY_CTR_DEFAULT,
                List.of(),                  // pipaJurisdictionAllowlist
                null,                       // legalBasisCode
                null,                       // travelRuleProtocol
                null,                       // travelRuleEndpointUrl
                STATUTORY_TRAVEL_RULE_DEFAULT);
    }
}
