package com.gme.pay.ratefx.xe;

import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link XeRateFetchScheduler}: verifies it upserts LIVE snapshots
 * for each currency returned by the client, and that source='LIVE'.
 */
class XeRateFetchSchedulerTest {

    @Test
    void fetchAndUpsert_savesLiveSnapshotForEachCurrency() {
        XeRateClient client = mock(XeRateClient.class);
        RateSnapshotRepository repo = mock(RateSnapshotRepository.class);

        XeMultiRateResponse fakeResp = new XeMultiRateResponse(
                "USD",
                "2026-06-13T10:00:00+09:00",
                "SIM_XE",
                Map.of("KRW", "1380.000000", "MNT", "3450.000000", "SGD", "1.350000"));
        when(client.fetchUsdRates()).thenReturn(fakeResp);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        XeRateFetchScheduler scheduler = new XeRateFetchScheduler(client, repo);
        scheduler.fetchAndUpsert();

        ArgumentCaptor<RateSnapshotEntity> captor =
                ArgumentCaptor.forClass(RateSnapshotEntity.class);
        verify(repo, times(3)).save(captor.capture());

        List<RateSnapshotEntity> saved = captor.getAllValues();
        assertThat(saved).allSatisfy(e -> assertThat(e.getSource()).isEqualTo("LIVE"));
        assertThat(saved).extracting(RateSnapshotEntity::getCurrencyCode)
                .containsExactlyInAnyOrder("KRW", "MNT", "SGD");
    }

    @Test
    void fetchAndUpsert_nullResponse_doesNotThrow() {
        XeRateClient client = mock(XeRateClient.class);
        RateSnapshotRepository repo = mock(RateSnapshotRepository.class);

        when(client.fetchUsdRates()).thenReturn(null);

        XeRateFetchScheduler scheduler = new XeRateFetchScheduler(client, repo);
        // Must not throw — the service must survive a down sim
        scheduler.fetchAndUpsert();

        verify(repo, never()).save(any());
    }
}
