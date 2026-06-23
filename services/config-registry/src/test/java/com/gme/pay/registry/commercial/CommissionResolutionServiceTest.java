package com.gme.pay.registry.commercial;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.contracts.EffectiveCommissionView;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.registry.scheme.SchemeCommissionShareEntity;
import com.gme.pay.registry.scheme.SchemeCommissionShareRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Slice test for {@link CommissionResolutionService} — the effective two-sided
 * commission resolution + exact-over-wildcard precedence (V031). Seeds the two
 * commission tables directly and asserts the resolved split per the documented
 * precedence.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommissionResolutionService.class, PartnerStore.class, CacheConfig.class})
class CommissionResolutionServiceTest {

    @Autowired
    private CommissionResolutionService service;

    @Autowired
    private SchemeCommissionShareRepository schemeRepo;

    @Autowired
    private PartnerCommissionShareRepository partnerRepo;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private void scheme(String schemeId, String dir, String gme, String van) {
        SchemeCommissionShareEntity e = new SchemeCommissionShareEntity();
        e.setSchemeId(schemeId);
        e.setDirection(dir);
        e.setGmeSharePct(new BigDecimal(gme));
        e.setVanFeePct(new BigDecimal(van));
        schemeRepo.save(e);
    }

    private void partner(Long partnerId, String schemeId, String dir, String pct) {
        PartnerCommissionShareEntity e = new PartnerCommissionShareEntity();
        e.setPartnerId(partnerId);
        e.setSchemeId(schemeId);
        e.setDirection(dir);
        e.setPartnerSharePct(new BigDecimal(pct));
        partnerRepo.save(e);
    }

    @Test
    void schemeSide_caseInsensitiveSchemeCode() {
        // Rows store canonical "ZEROPAY"; the payment path supplies raw "zeropay".
        // The scheme side must still resolve (else the consumer snapshots no share).
        Long pid = seedPartner("P_CASE");
        scheme("ZEROPAY", null, "0.7000", "0.0000");
        partner(pid, null, null, "0.2000");
        EffectiveCommissionView v = service.resolve("zeropay", "P_CASE", "OUTBOUND");
        assertThat(v.gmeSharePct()).isEqualByComparingTo("0.7000");
        assertThat(v.partnerSharePct()).isEqualByComparingTo("0.2000");
        assertThat(v.resolved()).isTrue();
    }

    @Test
    void schemeSide_exactDirectionBeatsWildcard() {
        Long pid = seedPartner("P_SCHEME");
        scheme("ZEROPAY", "INBOUND", "0.7000", "0.0008");
        scheme("ZEROPAY", null, "0.6000", "0.0005"); // wildcard
        partner(pid, null, null, "0.2000");

        // exact INBOUND row wins
        EffectiveCommissionView in = service.resolve("ZEROPAY", "P_SCHEME", "INBOUND");
        assertThat(in.gmeSharePct()).isEqualByComparingTo("0.7000");
        assertThat(in.vanFeePct()).isEqualByComparingTo("0.0008");
        assertThat(in.schemeShareSource()).isEqualTo("ZEROPAY:INBOUND");

        // OUTBOUND has no exact row → falls back to the direction=NULL wildcard
        EffectiveCommissionView out = service.resolve("ZEROPAY", "P_SCHEME", "OUTBOUND");
        assertThat(out.gmeSharePct()).isEqualByComparingTo("0.6000");
        assertThat(out.schemeShareSource()).isEqualTo("ZEROPAY:*");

        // both sides resolved
        assertThat(in.resolved()).isTrue();
        assertThat(in.partnerSharePct()).isEqualByComparingTo("0.2000");
    }

    @Test
    void partnerSide_mostSpecificWins() {
        Long pid = seedPartner("P_PART");
        scheme("ZEROPAY", null, "0.7000", "0.0000");
        partner(pid, "ZEROPAY", "OUTBOUND", "0.3000"); // score 3
        partner(pid, "ZEROPAY", null, "0.2500");       // score 2
        partner(pid, null, null, "0.1000");            // score 0

        // exact (scheme, direction)
        assertThat(service.resolve("ZEROPAY", "P_PART", "OUTBOUND").partnerSharePct())
                .isEqualByComparingTo("0.3000");
        // (scheme, *) — no exact direction row for INBOUND
        EffectiveCommissionView inb = service.resolve("ZEROPAY", "P_PART", "INBOUND");
        assertThat(inb.partnerSharePct()).isEqualByComparingTo("0.2500");
        assertThat(inb.partnerShareSource()).isEqualTo("P_PART:ZEROPAY:*");
        // different scheme → only the (*,*) row applies
        assertThat(service.resolve("KHQR", "P_PART", "OUTBOUND").partnerSharePct())
                .isEqualByComparingTo("0.1000");
    }

    @Test
    void unconfiguredSides_yieldNull_andResolvedFalse() {
        seedPartner("P_EMPTY");
        scheme("ZEROPAY", null, "0.7000", "0.0000");
        // partner has NO commission rows
        EffectiveCommissionView v = service.resolve("ZEROPAY", "P_EMPTY", "OUTBOUND");
        assertThat(v.gmeSharePct()).isEqualByComparingTo("0.7000");
        assertThat(v.partnerSharePct()).isNull();
        assertThat(v.partnerShareSource()).isEqualTo("none");
        assertThat(v.resolved()).isFalse();

        // unknown scheme → scheme side null
        EffectiveCommissionView noScheme = service.resolve("NOPE", "P_EMPTY", "OUTBOUND");
        assertThat(noScheme.gmeSharePct()).isNull();
        assertThat(noScheme.schemeShareSource()).isEqualTo("none");
    }

    @Test
    void unknownPartner_isLenient_noException() {
        scheme("ZEROPAY", null, "0.7000", "0.0000");
        EffectiveCommissionView v = service.resolve("ZEROPAY", "GHOST", null);
        assertThat(v.gmeSharePct()).isEqualByComparingTo("0.7000");
        assertThat(v.partnerSharePct()).isNull();
        assertThat(v.resolved()).isFalse();
    }
}
