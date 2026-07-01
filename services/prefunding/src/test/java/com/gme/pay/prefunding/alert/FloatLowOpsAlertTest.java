package com.gme.pay.prefunding.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.prefunding.client.StubConfigRegistryClient;
import com.gme.pay.prefunding.outbox.OutboxEntity;
import com.gme.pay.prefunding.outbox.OutboxRepository;
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
 * #5 — a low-balance crossing ALSO emits an {@code OpsAlertPayload}(alertType=FLOAT_LOW) onto the ops
 * topic ({@code gmepay.ops.alert}), converged with the existing per-partner {@code prefunding.alert}
 * (which is NOT removed). Severity scales with how far below threshold the float has fallen.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@ActiveProfiles("test")
class FloatLowOpsAlertTest {

    private static final String PARTNER = "FLOAT_P1";

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

    private OutboxEntity opsAlert() {
        return outbox.findAll().stream()
                .filter(e -> TierAlertEvaluator.EVENT_TYPE_OPS_ALERT.equals(e.getEventType()))
                .findFirst().orElseThrow(() -> new AssertionError("no ops.alert outbox row"));
    }

    @Test
    @DisplayName("crossing 70% emits FLOAT_LOW (WARN) on gmepay.ops.alert alongside prefunding.alert")
    void crossing70_emitsFloatLowWarn() {
        balances.save(new PartnerBalanceEntity(PARTNER, "USD", new BigDecimal("720.0000"),
                new BigDecimal("1000.0000"), Instant.now()));

        service.deduct(PARTNER, "txn-1", new BigDecimal("40.0000")); // 680 = 68%

        List<OutboxEntity> events = outbox.findAll();
        // Both the existing tier alert AND the converged ops alert are present.
        assertEquals(1, events.stream()
                .filter(e -> TierAlertEvaluator.EVENT_TYPE_ALERT.equals(e.getEventType())).count());
        OutboxEntity ops = opsAlert();
        assertEquals(PARTNER, ops.getAggregateId());
        String p = ops.getPayload();
        assertTrue(p.contains("\"eventType\":\"ops.alert\""), p);
        assertTrue(p.contains("\"alertType\":\"FLOAT_LOW\""), p);
        assertTrue(p.contains("\"severity\":\"WARN\""), p);
        assertTrue(p.contains("\"subjectRef\":\"" + PARTNER + "\""), p);
        assertTrue(p.contains("balanceUsd=680") && p.contains("thresholdUsd=1000"), p);
    }

    @Test
    @DisplayName("crossing only 95% emits FLOAT_LOW with INFO severity")
    void crossing95_emitsInfoSeverity() {
        balances.save(new PartnerBalanceEntity(PARTNER, "USD", new BigDecimal("1000.0000"),
                new BigDecimal("1000.0000"), Instant.now()));

        service.deduct(PARTNER, "txn-1", new BigDecimal("60.0000")); // 940 = 94%

        assertTrue(opsAlert().getPayload().contains("\"severity\":\"INFO\""));
    }

    @Test
    @DisplayName("BREACH (balance < 0) emits FLOAT_LOW with CRITICAL severity")
    void breach_emitsCriticalSeverity() {
        balances.save(new PartnerBalanceEntity(PARTNER, "USD", new BigDecimal("10.0000"),
                new BigDecimal("1000.0000"), Instant.now()));
        PartnerBalanceEntity row = balances.findById(PARTNER).orElseThrow();
        BigDecimal previous = row.getBalance();
        row.setBalance(new BigDecimal("-5.0000"));
        PartnerBalanceEntity saved = balances.save(row);

        evaluator.afterBalanceChange(saved, previous);

        assertTrue(opsAlert().getPayload().contains("\"severity\":\"CRITICAL\""));
    }
}
