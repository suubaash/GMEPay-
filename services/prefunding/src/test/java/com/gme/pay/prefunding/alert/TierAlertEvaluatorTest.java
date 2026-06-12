package com.gme.pay.prefunding.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.prefunding.client.StubConfigRegistryClient;
import com.gme.pay.prefunding.outbox.OutboxEntity;
import com.gme.pay.prefunding.outbox.OutboxRepository;
import com.gme.pay.prefunding.persistence.BalanceAlertEntity;
import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import com.gme.pay.prefunding.service.PrefundingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Slice 5 (5B.1) — tier-alert semantics against the Flyway-managed H2 database:
 *
 * <ol>
 *   <li>crossing DOWN through 70% (72% → 68%) raises TIER_70 exactly once — and ONLY
 *       TIER_70: 85/95 were not crossed by that mutation;</li>
 *   <li>oscillation around the boundary (68 → 71 → 69) does NOT re-raise — hysteresis
 *       anchors on the latest unacknowledged alert per (partner, tier);</li>
 *   <li>acknowledging the alert re-arms the tier: the next downward crossing raises a
 *       fresh TIER_70;</li>
 *   <li>every raised alert enqueues exactly one outbox row with event type
 *       {@code prefunding.alert} (transactional Outbox; topic {@code gmepay.prefunding.alert}
 *       once the Kafka publisher is wired);</li>
 *   <li>BREACH (balance &lt; 0) raises the BREACH alert and proposes a SYSTEM
 *       change_request (status=SUSPENDED, proposed_by='system') through the
 *       {@link StubConfigRegistryClient} — asserted on the recorded proposal.</li>
 * </ol>
 *
 * <p>Uses the default stub config-registry client (no {@code gmepay.config-registry.client=rest})
 * so no upstream service is needed.
 */
// Park the outbox drain (1h) so assertions on outbox rows never race the @Scheduled tick.
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@ActiveProfiles("test")
class TierAlertEvaluatorTest {

    private static final String PARTNER = "TIER_P1";

    @Autowired private PrefundingService service;
    @Autowired private TierAlertEvaluator evaluator;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private BalanceAlertRepository alerts;
    @Autowired private OutboxRepository outbox;
    @Autowired private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void cleanSlate() {
        outbox.deleteAll();
        alerts.deleteAll();
        ledger.deleteAll();
        balances.deleteAll();
        configRegistry.clear();
    }

    private void seed(String balance, String threshold) {
        balances.save(new PartnerBalanceEntity(
                PARTNER, "USD", new BigDecimal(balance), new BigDecimal(threshold),
                Instant.now()));
    }

    @Test
    @DisplayName("72% -> 68% of threshold raises TIER_70 exactly once (and only TIER_70)")
    void crossingDownThrough70_raisesTier70Once() {
        seed("720.0000", "1000.0000");

        service.deduct(PARTNER, "txn-1", new BigDecimal("40.0000")); // 680 = 68%

        List<BalanceAlertEntity> raised = alerts.findAll();
        assertEquals(1, raised.size(), "exactly one alert expected");
        BalanceAlertEntity alert = raised.get(0);
        assertEquals(TierAlertEvaluator.TIER_70, alert.getTier());
        assertEquals(PARTNER, alert.getPartnerCode());
        assertEquals(0, alert.getBalanceUsd().compareTo(new BigDecimal("680.0000")));
        assertEquals(0, alert.getThresholdUsd().compareTo(new BigDecimal("1000.0000")));
        assertFalse(alert.isAcknowledged());

        List<OutboxEntity> events = outbox.findAll();
        assertEquals(1, events.size(), "one outbox event per raised alert");
        assertEquals(TierAlertEvaluator.EVENT_TYPE_ALERT, events.get(0).getEventType());
        assertEquals(PARTNER, events.get(0).getAggregateId());
        assertTrue(events.get(0).getPayload().contains("\"tier\":\"TIER_70\""));
        // partner_balance is NUMERIC(20,8), so the in-tx arithmetic carries scale 8.
        assertTrue(events.get(0).getPayload().contains("\"balanceUsd\":\"680.00000000\""));
    }

    @Test
    @DisplayName("oscillation 68% -> 71% -> 69% does NOT re-raise TIER_70 (hysteresis)")
    void oscillationAroundBoundary_doesNotReraise() {
        seed("720.0000", "1000.0000");
        service.deduct(PARTNER, "txn-1", new BigDecimal("40.0000")); // 680 -> TIER_70
        assertEquals(1, alerts.countByPartnerCodeAndTier(PARTNER, TierAlertEvaluator.TIER_70));

        service.credit(PARTNER, new BigDecimal("30.0000"));          // 710 (back above 70%)
        service.deduct(PARTNER, "txn-2", new BigDecimal("20.0000")); // 690 (below again)

        assertEquals(1, alerts.countByPartnerCodeAndTier(PARTNER, TierAlertEvaluator.TIER_70),
                "latest TIER_70 alert is unacknowledged -> tier stays suppressed");
        assertEquals(1, alerts.count(), "no other tier crossed either");
        assertEquals(1, outbox.count(), "no extra outbox event for the suppressed crossing");
    }

    @Test
    @DisplayName("acknowledging the latest alert re-arms the tier for the next crossing")
    void acknowledgedAlert_rearmsTier() {
        seed("720.0000", "1000.0000");
        service.deduct(PARTNER, "txn-1", new BigDecimal("40.0000")); // 680 -> TIER_70

        BalanceAlertEntity first = alerts.findAll().get(0);
        first.setAcknowledged(true);
        alerts.save(first);

        service.credit(PARTNER, new BigDecimal("30.0000"));          // 710
        service.deduct(PARTNER, "txn-2", new BigDecimal("20.0000")); // 690 -> crossing again

        assertEquals(2, alerts.countByPartnerCodeAndTier(PARTNER, TierAlertEvaluator.TIER_70),
                "acknowledged alert re-arms the tier");
    }

    @Test
    @DisplayName("a single mutation crossing only 95% raises TIER_95 and nothing else")
    void crossing95Only_raisesTier95() {
        seed("1000.0000", "1000.0000");

        service.deduct(PARTNER, "txn-1", new BigDecimal("60.0000")); // 940 = 94%

        List<BalanceAlertEntity> raised = alerts.findAll();
        assertEquals(1, raised.size());
        assertEquals(TierAlertEvaluator.TIER_95, raised.get(0).getTier());
    }

    @Test
    @DisplayName("BREACH raises the alert and proposes a system change_request (SUSPENDED)")
    void breach_raisesAlertAndProposesSystemSuspension() {
        seed("10.0000", "1000.0000");
        // deduct() refuses to go negative, so drive the evaluator directly the way a
        // future adjustment/fee path would: balance already mutated below zero.
        PartnerBalanceEntity row = balances.findById(PARTNER).orElseThrow();
        BigDecimal previous = row.getBalance();
        row.setBalance(new BigDecimal("-5.0000"));
        PartnerBalanceEntity saved = balances.save(row);

        evaluator.afterBalanceChange(saved, previous);

        assertEquals(1, alerts.countByPartnerCodeAndTier(PARTNER, TierAlertEvaluator.BREACH));
        // 10 -> -5 crosses no tier boundary (10 was already below 70% of 1000).
        assertEquals(1, alerts.count(), "only the BREACH alert fires");

        List<StubConfigRegistryClient.Proposal> proposals = configRegistry.proposals();
        assertEquals(1, proposals.size(), "exactly one suspension proposal");
        StubConfigRegistryClient.Proposal proposal = proposals.get(0);
        assertEquals("partner", proposal.aggregateType());
        assertEquals(PARTNER, proposal.aggregateId());
        assertEquals("system", proposal.proposedBy());
        assertTrue(proposal.payloadJsonb().contains("SUSPENDED"));

        // Re-evaluating while still negative must not double-raise or double-propose.
        evaluator.afterBalanceChange(saved, saved.getBalance());
        assertEquals(1, alerts.countByPartnerCodeAndTier(PARTNER, TierAlertEvaluator.BREACH));
        assertEquals(1, configRegistry.proposals().size());
    }
}
