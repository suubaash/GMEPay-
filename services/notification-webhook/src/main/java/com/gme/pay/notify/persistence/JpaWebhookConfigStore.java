package com.gme.pay.notify.persistence;

import com.gme.pay.notify.domain.WebhookConfigEntry;
import com.gme.pay.notify.domain.WebhookConfigStore;
import com.gme.pay.notify.dto.WebhookConfigResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JPA-backed {@link WebhookConfigStore} adapter persisting to the
 * {@code webhook_endpoint} table (V003) &mdash; 17.2-G11.
 *
 * <p>{@code @Primary} so it wins over the Phase-1 {@code InMemoryWebhookConfigStore}
 * without changing the controller wiring; the in-memory implementation remains
 * available for tests that construct it directly.
 *
 * <p>The HMAC signing secret carried on {@link WebhookConfigEntry} is intentionally
 * <strong>not</strong> persisted (Vault is the secret store; see API-05 &sect;6.1).
 * Event types are stored as a comma-separated list; {@code null}/empty means
 * "subscribe to all events".
 */
@Component
@Primary
public class JpaWebhookConfigStore implements WebhookConfigStore {

    private final WebhookEndpointRepository repository;
    private final Clock clock;

    public JpaWebhookConfigStore(WebhookEndpointRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public WebhookConfigResponse save(WebhookConfigEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        Instant now = Instant.now(clock);
        WebhookEndpointEntity entity = new WebhookEndpointEntity();
        entity.setPartnerId(entry.partnerId());
        entity.setWebhookUrl(entry.webhookUrl());
        entity.setEventTypesCsv(toCsv(entry.eventTypes()));
        entity.setActive(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toResponse(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookConfigResponse> findActiveByPartnerId(Long partnerId) {
        return repository.findByPartnerIdAndActiveTrue(partnerId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WebhookConfigResponse> findById(Long id) {
        return repository.findById(id).map(this::toResponse);
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        repository.findById(id).ifPresent(entity -> {
            entity.setActive(false);
            entity.setUpdatedAt(Instant.now(clock));
            repository.save(entity);
        });
    }

    private WebhookConfigResponse toResponse(WebhookEndpointEntity entity) {
        return new WebhookConfigResponse(
                entity.getId(),
                entity.getPartnerId(),
                entity.getWebhookUrl(),
                fromCsv(entity.getEventTypesCsv()),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /** {@code null}/empty list (= "all events") is stored as SQL {@code NULL}. */
    public static String toCsv(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return null;
        }
        List<String> cleaned = eventTypes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        for (String type : cleaned) {
            if (type.contains(",")) {
                throw new IllegalArgumentException("event type must not contain a comma: " + type);
            }
        }
        return String.join(",", cleaned);
    }

    /** SQL {@code NULL} (= "all events") maps back to a {@code null} list. */
    public static List<String> fromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
