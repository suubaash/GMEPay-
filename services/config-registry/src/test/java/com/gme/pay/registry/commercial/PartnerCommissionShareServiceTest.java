package com.gme.pay.registry.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.contracts.PartnerCommissionShareCommand;
import com.gme.pay.contracts.PartnerCommissionShareView;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.audit.RecordingAuditPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice test for {@link PartnerCommissionShareService} — the configurable
 * GME ↔ partner commission split ({@code partner_commission_share}, V031),
 * wired end-to-end against H2 in PostgreSQL mode with the full Flyway chain.
 * Mirrors {@code FeeScheduleServiceTest}: {@code @DataJpaTest} + explicit
 * {@code @Import} of the service/audit beans and a {@link RecordingAuditPublisher}.
 *
 * <p>Pins: bulk-replace SCD-6 (second save supersedes the whole set sharing one
 * MICROS instant), empty-list clears, share normalized to scale 4, and the
 * validation envelope (direction roster, share in [0,1], &le; 4 dp, duplicate
 * keys, unknown partner 404), plus one audit event per replace.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerCommissionShareServiceTest.TestConfig.class, PartnerCommissionShareService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerCommissionShareServiceTest {

    @Autowired
    private PartnerCommissionShareService service;

    @Autowired
    private PartnerCommissionShareRepository repository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        com.gme.pay.audit.AuditPublisher auditPublisher(RecordingAuditPublisher recording) {
            return recording;
        }
    }

    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static PartnerCommissionShareCommand share(String scheme, String dir, String pct) {
        return new PartnerCommissionShareCommand(scheme, dir,
                pct == null ? null : new BigDecimal(pct));
    }

    @Test
    void replace_normalizesScale_andSecondReplaceSupersedesWholeSet() {
        Long partnerId = seedPartner("COMM_UPSERT");
        publisher.clear();

        List<PartnerCommissionShareView> first = service.replaceCommissionShares("COMM_UPSERT",
                List.of(share("ZEROPAY", "OUTBOUND", "0.30"),
                        share(null, null, "0.5")),
                "maker_kim");

        assertThat(first).hasSize(2);
        assertThat(first.get(0).schemeId()).isEqualTo("ZEROPAY");
        assertThat(first.get(0).direction()).isEqualTo("OUTBOUND");
        assertThat(first.get(0).partnerSharePct()).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(first.get(0).partnerSharePct().scale())
                .as("share normalized to NUMERIC(6,4) scale").isEqualTo(4);
        assertThat(first.get(1).schemeId()).as("wildcard default row").isNull();
        assertThat(first.get(1).direction()).isNull();

        // Replace with ONE row — supersedes the prior two.
        List<PartnerCommissionShareView> second = service.replaceCommissionShares("COMM_UPSERT",
                List.of(share("ZEROPAY", "BOTH", "0.25")), "maker_kim");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).direction()).isEqualTo("BOTH");

        // SCD-6: nothing deleted — 3 rows, the first two superseded at the fresh recorded_at.
        List<PartnerCommissionShareEntity> all = repository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(3);
        PartnerCommissionShareEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(all.stream().filter(e -> e.getSupersededAt() != null).toList())
                .hasSize(2)
                .allSatisfy(p -> assertThat(p.getSupersededAt()).isEqualTo(current.getRecordedAt()));
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();

        assertThat(service.currentCommissionShares("COMM_UPSERT")).hasSize(1);
        // One audit event per replace.
        assertThat(publisher.published()).hasSize(2);
    }

    @Test
    void emptyList_clearsAllRows() {
        seedPartner("COMM_CLEAR");
        service.replaceCommissionShares("COMM_CLEAR", List.of(share(null, null, "0.30")), "a");
        assertThat(service.currentCommissionShares("COMM_CLEAR")).hasSize(1);
        service.replaceCommissionShares("COMM_CLEAR", List.of(), "a");
        assertThat(service.currentCommissionShares("COMM_CLEAR")).isEmpty();
    }

    @Test
    void validation_rejectsBadInputs() {
        seedPartner("COMM_BAD");

        // null payload
        assertThatThrownBy(() -> service.replaceCommissionShares("COMM_BAD", null, "a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // bad direction
        assertBadRequest("COMM_BAD", share(null, "SIDEWAYS", "0.3"));
        // share > 1
        assertBadRequest("COMM_BAD", share(null, null, "1.5"));
        // share < 0
        assertBadRequest("COMM_BAD", share(null, null, "-0.1"));
        // null share
        assertBadRequest("COMM_BAD", share(null, null, null));
        // > 4 decimal places
        assertBadRequest("COMM_BAD", share(null, null, "0.12345"));

        // duplicate (scheme, direction)
        assertThatThrownBy(() -> service.replaceCommissionShares("COMM_BAD",
                List.of(share("ZEROPAY", "BOTH", "0.3"), share("ZEROPAY", "BOTH", "0.4")), "a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // no row should have been written by any rejected call
        assertThat(service.currentCommissionShares("COMM_BAD")).isEmpty();
    }

    @Test
    void unknownPartner_is404() {
        assertThatThrownBy(() -> service.currentCommissionShares("NOPE"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void boundaryShares_zeroAndOne_areAccepted() {
        seedPartner("COMM_BOUND");
        List<PartnerCommissionShareView> v = service.replaceCommissionShares("COMM_BOUND",
                List.of(share("ZEROPAY", "INBOUND", "0"), share("ZEROPAY", "OUTBOUND", "1")), "a");
        assertThat(v).hasSize(2);
        assertThat(v.get(0).partnerSharePct()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(v.get(1).partnerSharePct()).isEqualByComparingTo(BigDecimal.ONE);
    }

    private void assertBadRequest(String code, PartnerCommissionShareCommand cmd) {
        assertThatThrownBy(() -> service.replaceCommissionShares(code, List.of(cmd), "a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
