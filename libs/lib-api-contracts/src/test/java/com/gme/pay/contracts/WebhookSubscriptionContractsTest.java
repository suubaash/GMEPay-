package com.gme.pay.contracts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity tests for the Slice 8 Lane D webhook contract DTOs —
 * {@link WebhookSubscriptionCommand} / {@link WebhookSubscriptionView} /
 * {@link IssuedWebhookSubscription} /
 * {@link WebhookEndpointRegistrationCommand} /
 * {@link WebhookEndpointRegistrationView} /
 * {@link PartnerCommand.UpdateStep8WebhookSubscription}. These exercise the
 * seams in this module so a regression surfaces here rather than only
 * downstream in config-registry / notification-webhook.
 */
class WebhookSubscriptionContractsTest {

    @Test
    void updateStep8WebhookSubscriptionCarriesTheDraftSubscription() {
        WebhookSubscriptionCommand subscription = new WebhookSubscriptionCommand(
                "https://partner.example.com/hooks/gmepay",
                List.of("payment.approved", "payment.failed"),
                "SANDBOX");

        PartnerCommand.UpdateStep8WebhookSubscription cmd =
                new PartnerCommand.UpdateStep8WebhookSubscription(subscription);

        assertEquals(subscription, cmd.subscription());
        assertEquals("https://partner.example.com/hooks/gmepay", cmd.subscription().url());
        assertEquals(List.of("payment.approved", "payment.failed"),
                cmd.subscription().eventTypes());
        assertEquals("SANDBOX", cmd.subscription().environment());
    }

    @Test
    void commandToleratesAllEventsSubscription() {
        // null eventTypes = "subscribe to all events" (the webhook_endpoint
        // NULL-CSV convention) — the contract must not force a list.
        WebhookSubscriptionCommand subscription =
                new WebhookSubscriptionCommand("https://p.example.com/h", null, "LIVE");

        assertNull(subscription.eventTypes());
        assertEquals("LIVE", subscription.environment());
    }

    @Test
    void viewNeverNeedsSecretMaterial_andCarriesProvisioningState() {
        Instant now = Instant.parse("2026-06-13T03:00:00Z");
        WebhookSubscriptionView view = new WebhookSubscriptionView(
                7L, "SANDBOX", "https://p.example.com/h",
                List.of("payment.approved"), "42", "PROVISIONED", now, now, now);

        assertEquals(7L, view.id());
        assertEquals("42", view.endpointId());
        assertEquals("PROVISIONED", view.status());
        assertEquals(now, view.lastRotatedAt());
        // A DRAFT row has no endpoint nor rotation stamp yet.
        WebhookSubscriptionView draft = new WebhookSubscriptionView(
                8L, "LIVE", "https://p.example.com/h", null, null, "DRAFT",
                null, now, now);
        assertNull(draft.endpointId());
        assertNull(draft.lastRotatedAt());
    }

    @Test
    void issuedSubscription_secretOnlyOnTheNewlyProvisionedPath() {
        IssuedWebhookSubscription fresh = new IssuedWebhookSubscription(
                "SANDBOX", "42", "https://p.example.com/h",
                List.of("payment.approved"), "whsec_abc123", true);
        assertTrue(fresh.newlyProvisioned());
        assertEquals("whsec_abc123", fresh.signingSecretPlaintext());

        // The idempotent re-provision returns the existing endpoint and NO
        // new secret — the one-time-reveal contract.
        IssuedWebhookSubscription replay = new IssuedWebhookSubscription(
                "SANDBOX", "42", "https://p.example.com/h",
                List.of("payment.approved"), null, false);
        assertFalse(replay.newlyProvisioned());
        assertNull(replay.signingSecretPlaintext());
    }

    @Test
    void registrationWirePairRoundTripsTheCrossServiceFields() {
        WebhookEndpointRegistrationCommand request = new WebhookEndpointRegistrationCommand(
                99L, "https://p.example.com/h", List.of("payment.approved"), "LIVE");
        assertEquals(99L, request.partnerId());
        assertEquals("LIVE", request.environment());

        WebhookEndpointRegistrationView response =
                new WebhookEndpointRegistrationView("1001", "whsec_xyz", true);
        assertEquals("1001", response.endpointId());
        assertEquals("whsec_xyz", response.signingSecretPlaintext());
        assertTrue(response.newlyRegistered());
    }
}
