package com.gme.pay.settlement.alert;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.settlement.recon.MatchStatus;
import com.gme.pay.settlement.recon.ReconLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReconBreakAlerter} — the RECON_BREAK ops-alert emitter. Verifies alert shape,
 * severity derivation, and the clean-run no-emit contract using a capturing {@link EventPublisher}.
 */
class ReconBreakAlerterTest {

    /** In-memory publisher that captures every emitted event. */
    private static final class CapturingPublisher implements EventPublisher {
        final List<DomainEvent> events = new ArrayList<>();
        @Override public void publish(DomainEvent event) { events.add(event); }
    }

    private static ReconLine matched(String m) {
        return new ReconLine(m, new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, MatchStatus.MATCHED);
    }

    private static ReconLine discrepancy(String m, String amt) {
        return new ReconLine(m, new BigDecimal("100"), new BigDecimal("50"), new BigDecimal(amt), MatchStatus.DISCREPANCY);
    }

    private static ReconLine missingScheme(String m, String amt) {
        return new ReconLine(m, new BigDecimal(amt), null, new BigDecimal(amt), MatchStatus.MISSING_SCHEME);
    }

    @Test
    @DisplayName("clean batch (all MATCHED) emits no alert")
    void cleanBatch_noAlert() {
        CapturingPublisher pub = new CapturingPublisher();
        new ReconBreakAlerter(pub).alertOnBreak("B1", List.of(matched("MRC001"), matched("MRC002")));
        assertThat(pub.events).isEmpty();
    }

    @Test
    @DisplayName("a break emits a RECON_BREAK OpsAlert with subjectRef=batchId on topic ops.alert")
    void break_emitsReconBreakAlert() {
        CapturingPublisher pub = new CapturingPublisher();
        new ReconBreakAlerter(pub).alertOnBreak("B1", List.of(matched("MRC001"), discrepancy("MRC002", "720")));

        assertThat(pub.events).hasSize(1);
        OpsAlertPayload p = ((ReconAlertEvent) pub.events.get(0)).payload();
        assertThat(p.eventType()).isEqualTo(OpsAlertPayload.EVENT_TYPE);
        assertThat(p.alertType()).isEqualTo("RECON_BREAK");
        assertThat(p.subjectRef()).isEqualTo("B1");
        assertThat(p.detail()).contains("B1").contains("720");
        assertThat(p.occurredAt()).isNotBlank();
    }

    @Test
    @DisplayName("small low-value discrepancy → INFO severity")
    void smallDiscrepancy_info() {
        CapturingPublisher pub = new CapturingPublisher();
        new ReconBreakAlerter(pub).alertOnBreak("B1", List.of(discrepancy("MRC002", "720")));
        assertThat(((ReconAlertEvent) pub.events.get(0)).payload().severity()).isEqualTo("INFO");
    }

    @Test
    @DisplayName("any MISSING line → CRITICAL severity")
    void missingLine_critical() {
        CapturingPublisher pub = new CapturingPublisher();
        new ReconBreakAlerter(pub).alertOnBreak("B1", List.of(missingScheme("MRC002", "500")));
        assertThat(((ReconAlertEvent) pub.events.get(0)).payload().severity()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("high total break amount → CRITICAL severity")
    void largeAmount_critical() {
        CapturingPublisher pub = new CapturingPublisher();
        new ReconBreakAlerter(pub).alertOnBreak("B1", List.of(discrepancy("MRC002", "12000000")));
        assertThat(((ReconAlertEvent) pub.events.get(0)).payload().severity()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("many discrepancy lines → WARN severity")
    void manyLines_warn() {
        CapturingPublisher pub = new CapturingPublisher();
        List<ReconLine> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add(discrepancy("MRC" + i, "100"));
        }
        new ReconBreakAlerter(pub).alertOnBreak("B1", lines);
        assertThat(((ReconAlertEvent) pub.events.get(0)).payload().severity()).isEqualTo("WARN");
    }
}
