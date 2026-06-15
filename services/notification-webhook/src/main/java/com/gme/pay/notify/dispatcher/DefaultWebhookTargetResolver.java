package com.gme.pay.notify.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import com.gme.pay.notify.persistence.WebhookEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link WebhookTargetResolver}: resolves the endpoint URL from the
 * {@code webhook_endpoint} table and the signing secret from configuration.
 *
 * <p><b>Endpoint URL</b> — real: looked up by {@code (partnerId, environment)} from
 * the rows that config-registry registers at partner activation. The partner id is
 * read from the delivery row's payload ({@code partnerId} field). Environment is set
 * by {@code gmepay.webhook.environment} (default {@code LIVE}).
 *
 * <p><b>Signing secret</b> — the production design routes the one-time-reveal plaintext
 * to a Vault and fetches it here at dispatch time (the row only stores its hash). The
 * Vault is not yet stood up (audit P1), so this default reads
 * {@code gmepay.webhook.signing-secret} as an explicit local/dev stand-in. When unset
 * we return empty and the dispatcher leaves the row PENDING rather than sending an
 * unsigned/incorrectly-signed call. Swap this bean for a Vault-backed resolver in prod.
 */
@Component
public class DefaultWebhookTargetResolver implements WebhookTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebhookTargetResolver.class);

    private final WebhookEndpointRepository endpoints;
    private final String environment;
    private final String configuredSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultWebhookTargetResolver(
            WebhookEndpointRepository endpoints,
            @Value("${gmepay.webhook.environment:LIVE}") String environment,
            @Value("${gmepay.webhook.signing-secret:}") String configuredSecret) {
        this.endpoints = Objects.requireNonNull(endpoints);
        this.environment = environment;
        this.configuredSecret = configuredSecret;
    }

    @Override
    public Optional<ResolvedTarget> resolve(WebhookDeliveryEntity row) {
        Long partnerId = extractPartnerId(row.getPayload());
        if (partnerId == null) {
            log.warn("webhook target unresolved: no numeric partnerId in payload for webhookId={}",
                    row.getWebhookId());
            return Optional.empty();
        }

        List<WebhookEndpointEntity> active =
                endpoints.findByPartnerIdAndEnvironmentAndActiveTrue(partnerId, environment);
        if (active.isEmpty()) {
            log.warn("webhook target unresolved: no active {} endpoint for partnerId={} (webhookId={})",
                    environment, partnerId, row.getWebhookId());
            return Optional.empty();
        }
        String url = active.get(0).getWebhookUrl();

        if (configuredSecret == null || configuredSecret.isBlank()) {
            log.warn("webhook signing secret unavailable for partnerId={} env={}: production requires a "
                            + "Vault-backed WebhookTargetResolver; set gmepay.webhook.signing-secret for "
                            + "local/dev. Leaving webhookId={} PENDING.",
                    partnerId, environment, row.getWebhookId());
            return Optional.empty();
        }

        return Optional.of(new ResolvedTarget(url, configuredSecret));
    }

    /** Reads a numeric {@code partnerId} from the event payload; null if absent/non-numeric. */
    private Long extractPartnerId(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode node = root.get("partnerId");
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isNumber()) {
                return node.asLong();
            }
            String text = node.asText();
            return (text == null || text.isBlank()) ? null : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        } catch (Exception e) {
            log.debug("could not parse webhook payload for partnerId: {}", e.getMessage());
            return null;
        }
    }
}
