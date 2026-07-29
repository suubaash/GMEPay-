package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.PartnerDirectory;
import com.gme.pay.bff.client.PortalWebhookClient;
import com.gme.pay.bff.web.dto.WebhookConfigView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Production {@link PortalWebhookClient} — a partner's REAL webhook endpoints, read from
 * notification-webhook (gap register T1-3), replacing the two {@code partner.example.com} rows that
 * were hardcoded inline in {@code PartnerPortalController}.
 *
 * <h2>Endpoint mapping</h2>
 *
 * <p>notification-webhook's {@code WebhookConfigController}:
 * {@code GET /v1/webhook-configs?partnerId={long}} → {@code WebhookConfigListResponse}, backed by
 * {@code JpaWebhookConfigStore} over the {@code webhook_endpoint} table (the {@code @Primary} store;
 * the in-memory one is test-only). Only ACTIVE configs are returned by the upstream query.
 *
 * <p>The signing secret is never on that wire — notification-webhook stores only a digest and
 * discloses the derived {@code whsec_} value once, at registration/rotation. This client neither
 * requests nor maps any secret field.
 *
 * <h2>Partner scoping (security-critical)</h2>
 *
 * <p>The upstream keys endpoints by config-registry's numeric partner surrogate while the portal
 * carries the business code, so {@link PartnerDirectory} bridges them. An unresolvable code yields
 * an EMPTY list — the {@code partnerId} param is always present, so notification-webhook can never
 * be asked for every partner's endpoints.
 *
 * <h2>Field that genuinely has no source</h2>
 *
 * <p>{@link WebhookConfigView#lastDeliveredAt()} is returned as {@code null}: notification-webhook
 * exposes no per-endpoint last-delivery read (its delivery API is the aggregate backlog gauge plus
 * per-delivery replay). The inline stub used to print a literal
 * {@code 2026-06-09T11:00:00Z} there for every partner; absent is honest, and the Portal renders an
 * em dash. See the T1-3 report's residual list.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.notification-webhook.client", havingValue = "rest")
public class RestPortalWebhookClient implements PortalWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(RestPortalWebhookClient.class);

    /** Status label for an endpoint the upstream reports as active. */
    static final String STATUS_ACTIVE = "ACTIVE";

    /** Status label for a soft-deleted / deactivated endpoint. */
    static final String STATUS_INACTIVE = "INACTIVE";

    private final RestClient restClient;
    private final PartnerDirectory partners;

    @Autowired
    public RestPortalWebhookClient(
            @Value("${gmepay.notification-webhook.base-url:http://notification-webhook:8080}")
            String baseUrl,
            PartnerDirectory partners) {
        this(RestClient.builder().baseUrl(baseUrl).build(), partners);
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestPortalWebhookClient(RestClient restClient, PartnerDirectory partners) {
        this.restClient = restClient;
        this.partners = partners;
    }

    @Override
    public List<WebhookConfigView> listForPartner(String partnerCode) {
        Optional<Long> numericPartner = partners.numericIdOf(partnerCode);
        if (numericPartner.isEmpty()) {
            log.warn("webhooks: partner '{}' has no config-registry surrogate id — returning an "
                    + "empty list rather than an unscoped notification-webhook query", partnerCode);
            return List.of();
        }
        long partnerSurrogate = numericPartner.get();

        try {
            String uri = UriComponentsBuilder.fromPath("/v1/webhook-configs")
                    .queryParam("partnerId", partnerSurrogate)
                    .build().toUriString();
            WireList response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(WireList.class);
            if (response == null || response.configs() == null) {
                return List.of();
            }
            return response.configs().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(WireConfig::toView)
                    .toList();
        } catch (RestClientResponseException e) {
            log.warn("notification-webhook error listing webhooks for partner {} (status={})",
                    partnerSurrogate, e.getStatusCode());
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("notification-webhook unreachable listing webhooks for partner {}: {}",
                    partnerSurrogate, e.getMessage());
            return List.of();
        }
    }

    /** notification-webhook's {@code WebhookConfigListResponse} envelope. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireList(List<WireConfig> configs, Integer count) {}

    /**
     * notification-webhook's {@code WebhookConfigResponse}. Deliberately omits any secret field —
     * the upstream emits none and this record must not be able to carry one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireConfig(
            Long id,
            Long partnerId,
            String webhookUrl,
            List<String> eventTypes,
            Boolean active,
            Instant createdAt,
            Instant updatedAt) {

        WebhookConfigView toView() {
            return new WebhookConfigView(
                    webhookUrl,
                    // null eventTypes upstream means "subscribe to all events" (the
                    // webhook_endpoint convention); surface it as empty, not as an invented roster.
                    eventTypes == null ? List.of() : eventTypes,
                    Boolean.FALSE.equals(active) ? STATUS_INACTIVE : STATUS_ACTIVE,
                    // lastDeliveredAt: notification-webhook has no per-endpoint last-delivery read.
                    null);
        }
    }
}
