package com.gme.pay.ledger.consumer;

import com.gme.pay.ledger.revenue.InMemoryRevenueRecordStore;
import com.gme.pay.ledger.revenue.RevenueCaptureService;
import com.gme.pay.ledger.revenue.RevenueRecord;
import com.gme.pay.ledger.revenue.RevenueRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PaymentApprovedEventHandler}: the async payment.approved → revenue-capture
 * mapping. Runs broker-free against the in-memory store wrapped in the real capture service, so it
 * exercises the genuine idempotency path the Kafka consumer relies on.
 */
class PaymentApprovedEventHandlerTest {

    private RevenueRecordStore store;
    private PaymentApprovedEventHandler handler;

    @BeforeEach
    void setUp() {
        store = new InMemoryRevenueRecordStore();
        handler = new PaymentApprovedEventHandler(new RevenueCaptureService(store));
    }

    @Test
    void crossBorderEvent_capturesRevenueRow() {
        String payload = """
                {"eventType":"payment.approved","aggregateId":"TXN-100","occurredAt":"2026-06-15T10:00:00Z",
                 "txnRef":"TXN-100","partnerId":7,"schemeId":1,"revenueDate":"2026-06-15",
                 "collectionMarginUsd":"1.00","payoutMarginUsd":"0.50",
                 "serviceChargeAmount":"3.00","serviceChargeCcy":"USD","feeSharePct":"0.70"}
                """;

        boolean created = handler.handle("TXN-100", payload);

        assertThat(created).isTrue();
        Optional<RevenueRecord> stored = store.findByTxnRef("TXN-100");
        assertThat(stored).isPresent();
        RevenueRecord r = stored.get();
        assertThat(r.partnerId()).isEqualTo(7L);
        assertThat(r.schemeId()).isEqualTo(1L);
        assertThat(r.revenueDate()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(r.fxMarginUsd()).isEqualByComparingTo("1.50");   // 1.00 + 0.50
        assertThat(r.serviceChargeAmount()).isEqualByComparingTo("3.00");
        assertThat(r.serviceChargeCcy()).isEqualTo("USD");
    }

    @Test
    void duplicateEvent_isIdempotentSkip() {
        String payload = """
                {"eventType":"payment.approved","aggregateId":"TXN-200","occurredAt":"2026-06-15T10:00:00Z",
                 "partnerId":7,"collectionMarginUsd":"1.00","payoutMarginUsd":"0.50",
                 "serviceChargeAmount":"0","serviceChargeCcy":"USD","feeSharePct":"0.70"}
                """;

        assertThat(handler.handle("TXN-200", payload)).isTrue();   // first delivery creates
        assertThat(handler.handle("TXN-200", payload)).isFalse();  // redelivery is a no-op

        assertThat(store.countByPartnerAndDateRange(7L,
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 15))).isEqualTo(1L);
    }

    @Test
    void sameCurrencyEvent_recordsZeroFxMarginWithServiceCharge() {
        String payload = """
                {"eventType":"payment.approved","aggregateId":"TXN-300","occurredAt":"2026-06-15T10:00:00Z",
                 "txnRef":"TXN-300","partnerId":9,"schemeId":1,
                 "collectionMarginUsd":"0","payoutMarginUsd":"0",
                 "serviceChargeAmount":"500","serviceChargeCcy":"KRW","feeSharePct":"0.70"}
                """;

        handler.handle("TXN-300", payload);

        RevenueRecord r = store.findByTxnRef("TXN-300").orElseThrow();
        assertThat(r.fxMarginUsd()).isEqualByComparingTo("0");
        assertThat(r.serviceChargeAmount()).isEqualByComparingTo("500");
        assertThat(r.serviceChargeCcy()).isEqualTo("KRW");
    }

    @Test
    void revenueDateFallsBackToOccurredAtUtcDate_whenAbsent() {
        // occurredAt is 2026-06-16T01:00Z -> UTC date 2026-06-16; no explicit revenueDate.
        String payload = """
                {"eventType":"payment.approved","aggregateId":"TXN-400","occurredAt":"2026-06-16T01:00:00Z",
                 "txnRef":"TXN-400","partnerId":7,
                 "collectionMarginUsd":"1","payoutMarginUsd":"0",
                 "serviceChargeAmount":"0","serviceChargeCcy":"USD","feeSharePct":"0.70"}
                """;

        handler.handle("TXN-400", payload);

        assertThat(store.findByTxnRef("TXN-400").orElseThrow().revenueDate())
                .isEqualTo(LocalDate.of(2026, 6, 16));
    }

    @Test
    void txnRefFallsBackToRecordKey_whenPayloadOmitsIt() {
        String payload = """
                {"eventType":"payment.approved","occurredAt":"2026-06-15T10:00:00Z",
                 "partnerId":7,"collectionMarginUsd":"1","payoutMarginUsd":"0",
                 "serviceChargeAmount":"0","serviceChargeCcy":"USD","feeSharePct":"0.70"}
                """;

        handler.handle("KEY-TXN-500", payload);

        assertThat(store.findByTxnRef("KEY-TXN-500")).isPresent();
    }

    @Test
    void wrongEventType_isPoison() {
        String payload = """
                {"eventType":"payment.failed","aggregateId":"TXN-1","occurredAt":"2026-06-15T10:00:00Z"}
                """;
        assertThatThrownBy(() -> handler.handle("TXN-1", payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected eventType");
    }

    @Test
    void invalidJson_isPoison() {
        assertThatThrownBy(() -> handler.handle("TXN-1", "{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void emptyPayload_isPoison() {
        assertThatThrownBy(() -> handler.handle("TXN-1", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty payload");
    }

    @Test
    void missingTxnRefAndKey_isPoison() {
        String payload = """
                {"eventType":"payment.approved","occurredAt":"2026-06-15T10:00:00Z","partnerId":7}
                """;
        assertThatThrownBy(() -> handler.handle(null, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no txnRef");
    }

    @Test
    void nonNumericMoney_isPoison() {
        String payload = """
                {"eventType":"payment.approved","txnRef":"TXN-1","occurredAt":"2026-06-15T10:00:00Z",
                 "partnerId":7,"collectionMarginUsd":"abc","payoutMarginUsd":"0",
                 "serviceChargeAmount":"0","serviceChargeCcy":"USD","feeSharePct":"0.70"}
                """;
        assertThatThrownBy(() -> handler.handle("TXN-1", payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionMarginUsd");
    }

    @Test
    void negativeMargin_isPoison() {
        String payload = """
                {"eventType":"payment.approved","txnRef":"TXN-1","occurredAt":"2026-06-15T10:00:00Z",
                 "partnerId":7,"collectionMarginUsd":"-1","payoutMarginUsd":"0",
                 "serviceChargeAmount":"0","serviceChargeCcy":"USD","feeSharePct":"0.70"}
                """;
        assertThatThrownBy(() -> handler.handle("TXN-1", payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is invalid");
    }
}
