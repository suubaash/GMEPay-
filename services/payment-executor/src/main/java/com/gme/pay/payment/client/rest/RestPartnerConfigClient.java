package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.RoundingMode;

/**
 * REST adapter that calls config-registry's {@code GET /v1/partners/{id}} endpoint
 * (Phase-1 persistence wiring, replaces in-memory stubs).
 *
 * <p>Base URL is read from {@code gmepay.config-registry.base-url} (default
 * {@code http://config-registry:8080}). On non-2xx the upstream error is rethrown as
 * {@link PaymentException} so the orchestrator surfaces a consistent failure type.
 */
@Component
@Primary
public class RestPartnerConfigClient implements PartnerConfigClient {

    private final RestClient restClient;

    @Autowired
    public RestPartnerConfigClient(
            RestClient.Builder builder,
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** Package-private constructor used by tests that supply a pre-built RestClient. */
    RestPartnerConfigClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PartnerConfigView loadPartner(String partnerId) {
        try {
            PartnerConfigResponse body = restClient.get()
                    .uri("/v1/partners/{id}", partnerId)
                    .retrieve()
                    .body(PartnerConfigResponse.class);

            if (body == null) {
                throw new PaymentException("config-registry returned empty body for partner " + partnerId);
            }
            return body.toView();
        } catch (RestClientResponseException ex) {
            throw new PaymentException(
                    "config-registry GET /v1/partners/" + partnerId + " failed: "
                            + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (PaymentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentException(
                    "config-registry GET /v1/partners/" + partnerId + " failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public java.util.Optional<java.math.BigDecimal> resolveMerchantFeeRate(
            String schemeId, String merchantType) {
        if (schemeId == null || schemeId.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            MerchantFeeEffectiveResponse body = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/v1/schemes/{schemeId}/merchant-fees/effective");
                        if (merchantType != null && !merchantType.isBlank()) {
                            uriBuilder.queryParam("merchantType", merchantType);
                        }
                        return uriBuilder.build(schemeId);
                    })
                    .retrieve()
                    .body(MerchantFeeEffectiveResponse.class);
            if (body == null || !body.resolved()
                    || body.merchantFeePct() == null || body.merchantFeePct().isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new java.math.BigDecimal(body.merchantFeePct()));
        } catch (RuntimeException ex) {
            // Non-fatal: a merchant-fee resolution failure must NEVER fail a payment.
            // Leave the snapshot null; settlement treats it as 0 (today's behaviour).
            return java.util.Optional.empty();
        }
    }

    @Override
    public java.util.Optional<PartnerConfigClient.CommissionSplitConfig> resolveCommissionSplit(
            String schemeId, String partnerCode, String direction) {
        if (schemeId == null || schemeId.isBlank() || partnerCode == null || partnerCode.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            EffectiveCommissionResponse body = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/v1/commission/effective")
                                .queryParam("schemeId", schemeId)
                                .queryParam("partnerCode", partnerCode);
                        if (direction != null && !direction.isBlank()) {
                            uriBuilder.queryParam("direction", direction);
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(EffectiveCommissionResponse.class);
            // Only usable when BOTH sides resolved AND all three fractions are present.
            if (body == null || !body.resolved()
                    || body.gmeSharePct() == null || body.gmeSharePct().isBlank()
                    || body.vanFeePct() == null || body.vanFeePct().isBlank()
                    || body.partnerSharePct() == null || body.partnerSharePct().isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new PartnerConfigClient.CommissionSplitConfig(
                    new java.math.BigDecimal(body.gmeSharePct()),
                    new java.math.BigDecimal(body.vanFeePct()),
                    new java.math.BigDecimal(body.partnerSharePct())));
        } catch (RuntimeException ex) {
            // Non-fatal: a commission-resolution failure must NEVER fail a payment — skip the split.
            return java.util.Optional.empty();
        }
    }

    @Override
    public java.util.Optional<PartnerConfigClient.TxnLimits> resolveLimits(String partnerCode) {
        if (partnerCode == null || partnerCode.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            LimitsResponse body = restClient.get()
                    .uri("/v1/partners/{id}/limits", partnerCode)
                    .retrieve()
                    .body(LimitsResponse.class);
            if (body == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new PartnerConfigClient.TxnLimits(
                    parseMoney(body.perTxnMinUsd()), parseMoney(body.perTxnMaxUsd()),
                    parseMoney(body.dailyCapUsd()), parseMoney(body.monthlyCapUsd()),
                    parseMoney(body.annualCapUsd()), body.licenseType(), body.dailyTxnCountLimit()));
        } catch (RuntimeException ex) {
            // Fail-soft: 404 (no limits row) or unreachable → unconstrained (fail-open), per the
            // null-cap contract. NEVER fails a payment on a config-registry blip.
            return java.util.Optional.empty();
        }
    }

    /** Parse a decimal-string money field; null/blank → null (= cap not configured). */
    private static java.math.BigDecimal parseMoney(String v) {
        return (v == null || v.isBlank()) ? null : new java.math.BigDecimal(v);
    }

    /** Wire format for {@code GET /v1/schemes/{id}/merchant-fees/effective}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MerchantFeeEffectiveResponse(String merchantFeePct, boolean resolved) {}

    /** Wire format for {@code GET /v1/partners/{id}/limits} (config-registry LimitsView; money as decimal strings). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LimitsResponse(
            String perTxnMinUsd, String perTxnMaxUsd, String dailyCapUsd,
            String monthlyCapUsd, String annualCapUsd, String licenseType, Integer dailyTxnCountLimit) {}

    /** Wire format for {@code GET /v1/commission/effective} (config-registry EffectiveCommissionView). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EffectiveCommissionResponse(
            String gmeSharePct, String vanFeePct, String partnerSharePct, boolean resolved) {}

    /** Wire format for {@code GET /v1/partners/{id}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PartnerConfigResponse(
            String partnerId,
            String type,
            String settlementCurrency,
            String settlementRoundingMode
    ) {
        PartnerConfigView toView() {
            RoundingMode mode = settlementRoundingMode == null
                    ? RoundingMode.HALF_UP
                    : RoundingMode.valueOf(settlementRoundingMode);
            return new PartnerConfigView(partnerId, type, settlementCurrency, mode);
        }
    }
}
