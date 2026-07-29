package com.gme.pay.registry.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.kyb.KybEntity;
import com.gme.pay.registry.kyb.KybRepository;
import com.gme.pay.registry.lifecycle.ActivationGateService.ActivationGateResult;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The NON-PRODUCTION carve-out of the T1-4 activation gate:
 * {@code gmepay.activation.allow-unscreened-kyb=true}.
 *
 * <p>Separate class because the flag is a context-level property. The point of
 * these assertions is that the flag does NOT make the partner screened: the
 * sanctions condition stops blocking, but the gate hands back a non-null
 * {@link ActivationGateResult#unscreenedBasis()} that
 * {@link PartnerLifecycleChangeRequestApplier} writes to the audit log as
 * {@code PARTNER_ACTIVATED_UNSCREENED}. Default-off behaviour lives in
 * {@link ActivationGateServiceTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ActivationGateService.class, PartnerStore.class, CacheConfig.class})
@TestPropertySource(properties = "gmepay.activation.allow-unscreened-kyb=true")
class ActivationGateUnscreenedAllowanceTest {

    @Autowired
    private ActivationGateService gate;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private KybRepository kybRepository;

    private PartnerEntity seedPartnerWithUnscreenedKyb(String code) {
        partnerStore.save(Partner.of(code, PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP));
        PartnerEntity partner = partnerRepository.findCurrentByPartnerCode(code).orElseThrow();
        partner.setLegalNameLocal("지엠이 " + code);
        partner.setLegalNameRomanized("GME " + code + " Co., Ltd.");
        partner = partnerRepository.saveAndFlush(partner);

        KybEntity kyb = new KybEntity();
        kyb.setPartnerId(partner.getId());
        kyb.setRiskRating("MEDIUM");
        kyb.setScreeningStatus("NOT_SCREENED_NO_PROVIDER");
        kyb.setScreeningProviderId(ScreeningProvenance.STUB_PROVIDER_ID);
        kyb.setScreeningAuthoritative(false);
        kyb.setScreeningCaveat(ScreeningProvenance.STUB_CAVEAT);
        kyb.setScreenedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        kybRepository.saveAndFlush(kyb);
        return partner;
    }

    @Test
    @DisplayName("flag on: the sanctions condition stops blocking but the unscreened basis is returned")
    void flagOn_recordsTheUnscreenedBasis() {
        PartnerEntity partner = seedPartnerWithUnscreenedKyb("gate_allow_01");

        ActivationGateResult result = gate.check(partner);

        assertThat(result.unmet())
                .extracting(ActivationGateService.UnmetCondition::code)
                .doesNotContain(ActivationGateService.SANCTIONS_NOT_SCREENED,
                        ActivationGateService.SANCTIONS_NOT_CLEAR);
        // The carve-out never pretends the partner was screened.
        assertThat(result.unscreenedBasis())
                .isNotNull()
                .contains("ACTIVATED WITHOUT A SANCTIONS SCREENING")
                .contains("sanctions status is UNKNOWN")
                .contains("allow-unscreened-kyb");
    }

    @Test
    @DisplayName("flag on: an AUTHORITATIVE screening still yields no unscreened basis")
    void flagOn_realScreeningIsUnaffected() {
        partnerStore.save(Partner.of("gate_allow_02", PartnerType.LOCAL, "KRW",
                RoundingMode.HALF_UP));
        PartnerEntity partner =
                partnerRepository.findCurrentByPartnerCode("gate_allow_02").orElseThrow();
        KybEntity kyb = new KybEntity();
        kyb.setPartnerId(partner.getId());
        kyb.setRiskRating("LOW");
        kyb.setScreeningStatus("CLEAR");
        kyb.setScreeningProviderId("octa-test");
        kyb.setScreeningAuthoritative(true);
        kyb.setScreenedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        kybRepository.saveAndFlush(kyb);

        ActivationGateResult result = gate.check(partner);

        assertThat(result.unscreenedBasis())
                .as("a real screening is not an unscreened basis, flag or no flag")
                .isNull();
        assertThat(result.unmet())
                .extracting(ActivationGateService.UnmetCondition::code)
                .doesNotContain(ActivationGateService.SANCTIONS_NOT_SCREENED,
                        ActivationGateService.SANCTIONS_NOT_CLEAR);
    }
}
