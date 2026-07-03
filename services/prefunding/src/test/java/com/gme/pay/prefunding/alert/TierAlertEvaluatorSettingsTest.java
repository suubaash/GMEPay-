package com.gme.pay.prefunding.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.prefunding.client.ConfigRegistryClient;
import com.gme.pay.prefunding.outbox.OutboxWriter;
import com.gme.pay.prefunding.persistence.BalanceAlertEntity;
import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit test for {@link TierAlertEvaluator}'s config-registry-driven tier boundaries
 * (owner Goal #3 — runtime-tunable platform values). Uses plain Mockito fakes (no Spring /
 * DB) so the boundary-resolution logic is exercised in isolation from the crossing/hysteresis
 * DB semantics already covered by {@link TierAlertEvaluatorTest}.
 *
 * <p>The discriminating crossing is 86% → 82% of the threshold:
 * <ul>
 *   <li>with the historical fallback boundary 85, that crossing FIRES TIER_85 (86≥85, 82&lt;85);</li>
 *   <li>with a config-registry boundary of 80, it does NOT (82 is still above 80).</li>
 * </ul>
 * so the two cases below prove the evaluator reads config-registry when present and falls back
 * to 95/85/70 when the settings client returns {@code null} or throws.
 */
class TierAlertEvaluatorSettingsTest {

    private static final String PARTNER = "TIER_CFG";

    private PartnerBalanceEntity row(String balance) {
        PartnerBalanceEntity e = new PartnerBalanceEntity(
                PARTNER, "USD", new BigDecimal(balance), new BigDecimal("1000.0000"), Instant.now());
        return e;
    }

    private TierAlertEvaluator evaluator(BalanceAlertRepository alerts, OutboxWriter outbox,
                                         ConfigRegistryClient cfg) {
        // Every tier starts armed (no prior alert).
        when(alerts.findTopByPartnerCodeAndTierOrderByIdDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(alerts.save(any(BalanceAlertEntity.class))).thenAnswer(i -> i.getArgument(0));
        return new TierAlertEvaluator(alerts, outbox, cfg, new ObjectMapper());
    }

    @Test
    @DisplayName("uses config-registry boundaries when present (tier2=80 -> 86%->82% does NOT fire TIER_85)")
    void usesConfigRegistryBoundaries() {
        BalanceAlertRepository alerts = mock(BalanceAlertRepository.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        ConfigRegistryClient cfg = mock(ConfigRegistryClient.class);
        when(cfg.getSettingValue("prefunding.alert.tier1.pct")).thenReturn("90");
        when(cfg.getSettingValue("prefunding.alert.tier2.pct")).thenReturn("80");
        when(cfg.getSettingValue("prefunding.alert.tier3.pct")).thenReturn("60");

        TierAlertEvaluator ev = evaluator(alerts, outbox, cfg);
        // 86% -> 82%: below the fallback 85 but still above the configured 80.
        ev.afterBalanceChange(row("820.0000"), new BigDecimal("860.0000"));

        // No tier fired (82% is above every configured boundary 90/80/60 that was crossed:
        // tier1=90 was already below at 86%, so no downward crossing through it either).
        verify(alerts, never()).save(any());
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("falls back to 95/85/70 when the settings client returns null")
    void fallsBackWhenSettingAbsent() {
        BalanceAlertRepository alerts = mock(BalanceAlertRepository.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        ConfigRegistryClient cfg = mock(ConfigRegistryClient.class);
        when(cfg.getSettingValue(anyString())).thenReturn(null); // absent -> fallback

        TierAlertEvaluator ev = evaluator(alerts, outbox, cfg);
        ev.afterBalanceChange(row("820.0000"), new BigDecimal("860.0000"));

        // Fallback boundary 85: 86% -> 82% DOES cross down through 85, firing exactly TIER_85.
        ArgumentCaptor<BalanceAlertEntity> saved = ArgumentCaptor.forClass(BalanceAlertEntity.class);
        verify(alerts, times(1)).save(saved.capture());
        assertEquals(TierAlertEvaluator.TIER_85, saved.getValue().getTier());
    }

    @Test
    @DisplayName("falls back to 95/85/70 when the settings client throws")
    void fallsBackWhenSettingClientThrows() {
        BalanceAlertRepository alerts = mock(BalanceAlertRepository.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        ConfigRegistryClient cfg = mock(ConfigRegistryClient.class);
        when(cfg.getSettingValue(anyString())).thenThrow(new RuntimeException("registry down"));

        TierAlertEvaluator ev = evaluator(alerts, outbox, cfg);
        ev.afterBalanceChange(row("820.0000"), new BigDecimal("860.0000"));

        // Resilient: the throw is swallowed and the fallback 85 fires TIER_85 (no regression).
        ArgumentCaptor<BalanceAlertEntity> saved = ArgumentCaptor.forClass(BalanceAlertEntity.class);
        verify(alerts, times(1)).save(saved.capture());
        assertEquals(TierAlertEvaluator.TIER_85, saved.getValue().getTier());
    }

    @Test
    @DisplayName("malformed (non-numeric) setting value falls back to the default boundary")
    void fallsBackWhenSettingMalformed() {
        BalanceAlertRepository alerts = mock(BalanceAlertRepository.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        ConfigRegistryClient cfg = mock(ConfigRegistryClient.class);
        when(cfg.getSettingValue(anyString())).thenReturn("not-a-number");

        TierAlertEvaluator ev = evaluator(alerts, outbox, cfg);
        ev.afterBalanceChange(row("820.0000"), new BigDecimal("860.0000"));

        verify(alerts, times(1)).save(any());
    }
}
