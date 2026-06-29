package com.gme.pay.notify.dispatcher;

import com.gme.pay.notify.domain.RetryPolicy;
import com.gme.pay.notify.domain.WebhookSender;
import com.gme.pay.notify.domain.WebhookSender.WebhookDeliveryResult;
import com.gme.pay.notify.dispatcher.WebhookTargetResolver.ResolvedTarget;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebhookDispatcher} drain logic with mocked collaborators:
 * deliver -> markDelivered; failure -> markAttemptFailedOrDlq; unresolved target ->
 * leave PENDING (no send); backoff window not elapsed -> skip.
 */
class WebhookDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-06-15T00:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RetryPolicy retryPolicy = new RetryPolicy();
    private final WebhookSender sender = mock(WebhookSender.class);
    private final WebhookDeliveryRepository repo = mock(WebhookDeliveryRepository.class);
    private final WebhookPersistenceService persistence = mock(WebhookPersistenceService.class);
    private final WebhookTargetResolver resolver = mock(WebhookTargetResolver.class);

    private WebhookDispatcher dispatcher() {
        return new WebhookDispatcher(sender, repo, persistence, resolver, retryPolicy, clock, 200);
    }

    private static WebhookDeliveryEntity pendingRow(int attempt, Instant lastAttemptedAt) {
        WebhookDeliveryEntity row = new WebhookDeliveryEntity();
        row.setId(1L);
        row.setWebhookId("evt_1");
        row.setEventType("payment.approved");
        row.setPayload("{\"partnerId\":7}");
        row.setStatus("PENDING");
        row.setAttempt(attempt);
        row.setLastAttemptedAt(lastAttemptedAt);
        row.setCreatedAt(NOW);
        return row;
    }

    @Test
    void delivers_andMarksDelivered() {
        WebhookDeliveryEntity row = pendingRow(0, null);
        when(repo.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any())).thenReturn(List.of(row));
        when(resolver.resolve(row)).thenReturn(Optional.of(new ResolvedTarget("https://p/wh", "whsec_x")));
        when(sender.sendWithAttempt(eq("evt_1"), eq("payment.approved"), eq("https://p/wh"),
                any(), eq("whsec_x"), eq(1)))
                .thenReturn(WebhookDeliveryResult.of(200, "ok", 5));

        dispatcher().drainPending();

        verify(persistence).markDelivered(row, 1);
        verify(persistence, never()).markAttemptFailedOrDlq(any(), anyInt(), any());
    }

    @Test
    void failure_marksFailedOrDlq() {
        WebhookDeliveryEntity row = pendingRow(0, null);
        when(repo.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any())).thenReturn(List.of(row));
        when(resolver.resolve(row)).thenReturn(Optional.of(new ResolvedTarget("https://p/wh", "whsec_x")));
        when(sender.sendWithAttempt(anyString(), anyString(), anyString(), any(), anyString(), eq(1)))
                .thenReturn(WebhookDeliveryResult.of(500, "boom", 5));

        dispatcher().drainPending();

        verify(persistence).markAttemptFailedOrDlq(row, 1, "boom");
        verify(persistence, never()).markDelivered(any(), anyInt());
    }

    @Test
    void unresolvedTarget_advancesAttemptForEventualDlq() {
        // #92: an unresolved target now counts as a failed attempt (so it DLQs at the
        // ceiling) rather than retrying forever — but still sends nothing.
        WebhookDeliveryEntity row = pendingRow(0, null);
        when(repo.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any())).thenReturn(List.of(row));
        when(resolver.resolve(row)).thenReturn(Optional.empty());

        dispatcher().drainPending();

        verifyNoInteractions(sender);
        verify(persistence, never()).markDelivered(any(), anyInt());
        verify(persistence).markAttemptFailedOrDlq(eq(row), eq(1), anyString());
    }

    @Test
    void backoffWindowNotElapsed_skips() {
        // attempt 2 failed at NOW -> next retry is +30s, so NOW is too early to retry.
        WebhookDeliveryEntity row = pendingRow(2, NOW);
        when(repo.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any())).thenReturn(List.of(row));

        dispatcher().drainPending();

        verifyNoInteractions(resolver);
        verifyNoInteractions(sender);
    }
}
