package com.gme.pay.notify.domain;

import com.gme.pay.notify.dto.WebhookConfigResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe, in-memory implementation of {@link WebhookConfigStore}.
 *
 * <p>Used during local dev / testing before the JPA/PostgreSQL adapter is wired.
 * Does NOT persist across restarts.
 */
@Component
public class InMemoryWebhookConfigStore implements WebhookConfigStore {

    private final AtomicLong idSeq = new AtomicLong(1);
    private final Map<Long, StoredEntry> store = new ConcurrentHashMap<>();

    @Override
    public WebhookConfigResponse save(WebhookConfigEntry entry) {
        long id = idSeq.getAndIncrement();
        Instant now = Instant.now();
        StoredEntry stored = new StoredEntry(id, entry.partnerId(), entry.webhookUrl(),
                entry.eventTypes(), true, now, now);
        store.put(id, stored);
        return toResponse(stored);
    }

    @Override
    public List<WebhookConfigResponse> findActiveByPartnerId(Long partnerId) {
        return store.values().stream()
                .filter(e -> e.active && e.partnerId.equals(partnerId))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<WebhookConfigResponse> findById(Long id) {
        return Optional.ofNullable(store.get(id)).map(this::toResponse);
    }

    @Override
    public void deactivate(Long id) {
        store.computeIfPresent(id, (k, e) ->
                new StoredEntry(e.id, e.partnerId, e.webhookUrl, e.eventTypes, false, e.createdAt, Instant.now()));
    }

    private WebhookConfigResponse toResponse(StoredEntry e) {
        return new WebhookConfigResponse(e.id, e.partnerId, e.webhookUrl, e.eventTypes,
                e.active, e.createdAt, e.updatedAt);
    }

    private record StoredEntry(
            Long id,
            Long partnerId,
            String webhookUrl,
            List<String> eventTypes,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
