package com.gme.pay.ratefx.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.gme.pay.errors.ApiException;
import com.gme.pay.ratefx.audit.RateAuditor;
import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the manual-override / PARTNER-seed admin service. */
@ExtendWith(MockitoExtension.class)
class RateSnapshotAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-30T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private RateSnapshotRepository snapshots;

    /**
     * Gap T5-1: the service now also writes a hash-chained audit row naming who set the rate. This
     * slice covers VALIDATION (which currency codes / sources / amounts are accepted) and stays
     * DB-free, so the auditor is mocked here; the audit row and its actor are asserted for real in
     * {@code com.gme.pay.ratefx.audit.RateAuditTrailTest}.
     */
    @Mock
    private RateAuditor audit;

    private RateSnapshotAdminService service() {
        when(snapshots.save(any(RateSnapshotEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return new RateSnapshotAdminService(snapshots, clock, audit);
    }

    @Test
    void record_manualOverride_persistsRow() {
        RateSnapshotEntity saved = service().record("KRW", new BigDecimal("1350.00"), "MANUAL", null);
        assertThat(saved.getCurrencyCode()).isEqualTo("KRW");
        assertThat(saved.getSource()).isEqualTo("MANUAL");
        assertThat(saved.getUsdRate()).isEqualByComparingTo("1350.00");
        assertThat(saved.getEffectiveAt()).isEqualTo(NOW);
    }

    @Test
    void record_partnerSeed_persistsRow() {
        RateSnapshotEntity saved = service().record("krw", new BigDecimal("1395"), "partner", null);
        assertThat(saved.getCurrencyCode()).isEqualTo("KRW");
        assertThat(saved.getSource()).isEqualTo("PARTNER");
    }

    @Test
    void record_usd_rejected() {
        assertThatThrownBy(() ->
                new RateSnapshotAdminService(snapshots, clock, audit).record("USD", BigDecimal.ONE, "MANUAL", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void record_liveSource_rejected() {
        assertThatThrownBy(() ->
                new RateSnapshotAdminService(snapshots, clock, audit).record("KRW", BigDecimal.TEN, "LIVE", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void record_nonPositiveRate_rejected() {
        assertThatThrownBy(() ->
                new RateSnapshotAdminService(snapshots, clock, audit).record("KRW", BigDecimal.ZERO, "MANUAL", null))
                .isInstanceOf(ApiException.class);
    }
}
