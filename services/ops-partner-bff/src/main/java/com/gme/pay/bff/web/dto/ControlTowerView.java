package com.gme.pay.bff.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.contracts.OperationalStatusView;

import java.math.BigDecimal;
import java.util.List;

/**
 * The Operations control-tower composed read model — one page the on-call operator
 * watches. Aggregated by {@link com.gme.pay.bff.web.ControlTowerController} from the
 * gated upstream REST clients (transaction-mgmt, notification-webhook, prefunding,
 * system-health, settlement-reconciliation, config-registry).
 *
 * <p><b>Graceful degradation.</b> Each section carries its own {@code unavailable}
 * signal (null counts / {@code UNKNOWN} status) so one unreachable upstream shows that
 * section as "unknown" rather than 500ing the whole tower. {@code degradedSections}
 * lists the names of the sections that could not be fully composed.
 *
 * <p>{@code @JsonInclude(ALWAYS)} so null "unknown" fields stay on the wire — the UI
 * distinguishes "0" from "unknown".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ControlTowerView(
        InFlight inFlight,
        WebhookBacklog webhookBacklog,
        FloatHeadroom floatHeadroom,
        Health health,
        Integer openReconExceptions,
        OperationalStatusView operationalStatus,
        List<String> degradedSections
) {

    /** In-flight + attention-needing transaction counts. Null = section unavailable. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InFlight(Integer inFlightCount, Integer uncertainOrAgedCount) {}

    /** Webhook delivery backlog. Null counts = section unavailable. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WebhookBacklog(Integer pending, Integer dlq, Integer total) {}

    /**
     * Prefunding float headroom per partner + the single lowest / most-at-risk partner.
     * {@code partners} empty when unavailable; {@code lowest} null when none/unavailable.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FloatHeadroom(List<PartnerFloat> partners, PartnerFloat lowest) {}

    /**
     * One partner's float position. {@code pctOfThreshold} is balance/threshold*100
     * (null when no positive threshold); {@code atRisk} true when at/below threshold.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PartnerFloat(
            String partnerId,
            String currency,
            BigDecimal balance,
            BigDecimal threshold,
            BigDecimal pctOfThreshold,
            boolean atRisk
    ) {}

    /** Scheme/partner health rollup from the system-health probe fan-out. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Health(Integer total, Integer up, Integer down, Integer degraded, List<String> unhealthy) {}
}
