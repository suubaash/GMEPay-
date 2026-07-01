package com.gme.pay.ratefx.partnerb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the in-process Partner B quote port (WBS 4.6) over {@code rate_snapshots}. */
@ExtendWith(MockitoExtension.class)
class SnapshotPartnerBQuotePortTest {

    private static final Instant NOW = Instant.parse("2026-06-30T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private RateSnapshotRepository snapshots;

    private SnapshotPartnerBQuotePort port() {
        return new SnapshotPartnerBQuotePort(snapshots, clock);
    }

    @Test
    void fetchQuote_returnsLatestPartnerSnapshot() {
        RateSnapshotEntity row = new RateSnapshotEntity(
                "partner-krw-1", "KRW", new BigDecimal("1395.00000000"), "PARTNER", NOW, NOW);
        when(snapshots.findFirstByCurrencyCodeAndSourceAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
                "KRW", "PARTNER", NOW)).thenReturn(Optional.of(row));

        PartnerBQuote quote = port().fetchQuote("zeropay", "KRW");

        assertThat(quote.rate()).isEqualByComparingTo("1395.00000000");
        assertThat(quote.quoteReference()).isEqualTo("partner-krw-1");
    }

    @Test
    void fetchQuote_noPartnerRow_throwsUnavailable() {
        when(snapshots.findFirstByCurrencyCodeAndSourceAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
                "KRW", "PARTNER", NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> port().fetchQuote("zeropay", "KRW"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARTNER_B_QUOTE_UNAVAILABLE);
    }

    @Test
    void fetchQuote_blankCurrency_throwsUnavailable() {
        assertThatThrownBy(() -> port().fetchQuote("zeropay", "  "))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARTNER_B_QUOTE_UNAVAILABLE);
    }
}
