package com.gme.pay.notify.dispatcher;

import com.gme.pay.notify.consumer.PaymentReversedEventHandler;
import com.gme.pay.notify.domain.WebhookHttpClient;
import com.gme.pay.notify.domain.WebhookSender;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import com.gme.pay.notify.persistence.WebhookEndpointRepository;
import com.gme.pay.notify.provisioning.SigningSecrets;
import com.gme.pay.notify.provisioning.WebhookSecretDeriver;
import com.gme.pay.notify.signing.WebhookSigningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gap T2-6: a refund webhook must reach the partner <b>signed with that partner's endpoint secret</b> — and
 * it must get there through the EXISTING signing model, not a second one.
 *
 * <p>This is the Docker-free companion to {@code PaymentApprovedWebhookDeliveryIT}: it composes the REAL
 * {@link DefaultWebhookTargetResolver} (T5-4's per-endpoint HKDF derivation + digest verification), the REAL
 * {@link WebhookSigningService} and the REAL {@link WebhookSender}, with only the outbound socket
 * ({@link WebhookHttpClient}) doubled. So what is asserted is production signing behaviour applied to a
 * {@code payment.reversed} delivery row.
 *
 * <p>The point being pinned is that <b>nothing about signing was changed for refunds</b>: the refund is
 * signed with the secret derived for its own endpoint, verified against that endpoint's stored digest, and a
 * different partner's secret produces a different signature.
 */
class RefundWebhookSigningTest {

    private static final String ROOT_KEY = "root-key-for-tests-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
    private static final long PARTNER = 42L;
    private static final long OTHER_PARTNER = 43L;
    private static final String ENV = "LIVE";

    /** The refund event body, as transaction-mgmt emits it and the handler re-serialises it. */
    private static final String REFUND_PAYLOAD = """
            {"eventType":"payment.reversed","txnRef":"TXN-1","partnerId":"42","schemeId":"zeropay",\
            "reversedAmount":"20000","currency":"KRW","reversedUsd":"15.0000","reason":"CUSTOMER_REQUEST",\
            "source":"REFUND","occurredAt":"2026-07-28T08:30:00Z"}""";

    private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final WebhookSigningService signing = new WebhookSigningService();

    private String derivedSecretFor(long partnerId) {
        return WebhookSecretDeriver.withRootKey(ROOT_KEY)
                .derive(partnerId, ENV, WebhookSecretDeriver.INITIAL_GENERATION);
    }

    /** Registers an ACTIVE endpoint whose stored digest matches the secret derived under ROOT_KEY. */
    private WebhookEndpointEntity registerEndpoint(long partnerId, String url) {
        WebhookEndpointEntity ep = new WebhookEndpointEntity();
        ep.setPartnerId(partnerId);
        ep.setWebhookUrl(url);
        ep.setEnvironment(ENV);
        ep.setActive(true);
        ep.setSigningSecretHash(SigningSecrets.sha256Hex(derivedSecretFor(partnerId)));
        ep.setSecretGeneration(WebhookSecretDeriver.INITIAL_GENERATION);
        ep.setCreatedAt(NOW);
        ep.setUpdatedAt(NOW);
        when(endpoints.findByPartnerIdAndEnvironmentAndActiveTrue(partnerId, ENV))
                .thenReturn(List.of(ep));
        return ep;
    }

    private WebhookDeliveryEntity refundRow() {
        WebhookDeliveryEntity row = mock(WebhookDeliveryEntity.class);
        when(row.getPayload()).thenReturn(REFUND_PAYLOAD);
        when(row.getWebhookId()).thenReturn("TXN-1");
        return row;
    }

    @Test
    @DisplayName("a refund delivery is signed with THAT endpoint's derived secret")
    void refundIsSignedWithTheEndpointsOwnSecret() {
        registerEndpoint(PARTNER, "https://partner-42.example.com/webhooks/gmepay");

        DefaultWebhookTargetResolver resolver = new DefaultWebhookTargetResolver(
                endpoints, ENV, WebhookSecretDeriver.withRootKey(ROOT_KEY), clock);

        Optional<WebhookTargetResolver.ResolvedTarget> target = resolver.resolve(refundRow());
        assertTrue(target.isPresent(),
                "a refund row must resolve to the partner's endpoint — an unresolved row is never delivered");
        assertEquals("https://partner-42.example.com/webhooks/gmepay", target.get().url());

        AtomicReference<WebhookSender.WebhookRequest> sent = new AtomicReference<>();
        WebhookSender sender = new WebhookSender(signing, request -> {
            sent.set(request);
            return WebhookSender.WebhookDeliveryResult.of(200, "ok", 3);
        }, clock);

        byte[] body = REFUND_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        WebhookSender.WebhookDeliveryResult result = sender.sendWithAttempt(
                "TXN-1", PaymentReversedEventHandler.EVENT_TYPE, target.get().url(), body,
                target.get().secret(), target.get().secondarySecret(), 1);

        assertTrue(result.success());
        WebhookSender.WebhookRequest request = sent.get();
        assertNotNull(request.signatureHeader(), "a refund webhook must be signed");
        assertNotNull(request.timestampHeader());

        // The exact signature the partner will verify with the secret they were handed at registration.
        assertTrue(signing.verifySignature(REFUND_PAYLOAD, derivedSecretFor(PARTNER),
                        request.signatureHeader()),
                "the refund must verify under the secret derived for THIS endpoint");

        // And not under anyone else's — the T5-4 guarantee, unchanged by adding refunds.
        assertTrue(!signing.verifySignature(REFUND_PAYLOAD, derivedSecretFor(OTHER_PARTNER),
                        request.signatureHeader()),
                "another partner's secret must not verify this partner's refund");
        assertNotEquals(derivedSecretFor(OTHER_PARTNER), target.get().secret());

        // The partner receives the refund facts, not an opaque ping.
        String delivered = new String(request.payload(), StandardCharsets.UTF_8);
        assertTrue(delivered.contains("\"reversedAmount\":\"20000\""));
        assertTrue(delivered.contains("\"source\":\"REFUND\""));
    }

    @Test
    @DisplayName("a refund is NOT delivered when the endpoint's secret cannot be proven (fail closed)")
    void refundIsNotSignedWithAnUnprovableSecret() {
        registerEndpoint(PARTNER, "https://partner-42.example.com/webhooks/gmepay");

        // A resolver holding the WRONG root key can derive a secret, but it will not match the digest stored
        // at registration — so it must refuse rather than sign with something the partner does not hold.
        DefaultWebhookTargetResolver resolver = new DefaultWebhookTargetResolver(
                endpoints, ENV, WebhookSecretDeriver.withRootKey("a-different-root-key-000000000000"),
                clock);

        assertTrue(resolver.resolve(refundRow()).isEmpty(),
                "no refund may go out signed with an unverifiable secret; the row stays undelivered");
    }

    @Test
    @DisplayName("a refund for a partner with no registered endpoint resolves to nothing, silently and safely")
    void refundWithNoEndpointResolvesToNothing() {
        when(endpoints.findByPartnerIdAndEnvironmentAndActiveTrue(PARTNER, ENV))
                .thenReturn(List.of());

        DefaultWebhookTargetResolver resolver = new DefaultWebhookTargetResolver(
                endpoints, ENV, WebhookSecretDeriver.withRootKey(ROOT_KEY), clock);

        assertTrue(resolver.resolve(refundRow()).isEmpty());
    }
}
