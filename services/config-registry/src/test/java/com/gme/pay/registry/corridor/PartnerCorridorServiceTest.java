package com.gme.pay.registry.corridor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.PartnerCorridorCommand;
import com.gme.pay.contracts.PartnerCorridorView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 7 acceptance test for {@link PartnerCorridorService} — the
 * {@code partner_corridor} bulk replace (V023) wired end-to-end against H2 in
 * PostgreSQL mode with the full Flyway chain applied. Mirrors the
 * {@code RuleServiceTest} slice-test pattern: {@code @DataJpaTest} + explicit
 * {@code @Import} of the service/audit beans and a
 * {@link RecordingAuditPublisher} to observe ADR-007 publication.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Step-7 bulk replace inserts the CURRENT set; a second replace
 *       supersedes the whole prior set — paired SCD-6 writes sharing one
 *       MICROS-truncated instant; an empty list clears.</li>
 *   <li>{@code isActive} defaults TRUE when the wire omits it;
 *       {@code goLiveDate} is optional and round-trips.</li>
 *   <li>Field validation rejects bad ISO country / currency codes and
 *       duplicate lanes with 400, without touching any row.</li>
 *   <li>One {@code partner_corridor} audit event per write with BEFORE/AFTER
 *       canonical snapshots.</li>
 *   <li>Unknown partner → 404; partner outside ONBOARDING → 409.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerCorridorServiceTest.TestConfig.class, PartnerCorridorService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerCorridorServiceTest {

    @Autowired
    private PartnerCorridorService service;

    @Autowired
    private PartnerCorridorRepository corridorRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest} / {@code RuleServiceTest}. */
    @TestConfiguration
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

    // ------------------------------------------------------------------ helpers

    /** Create a partner draft through the canonical store path; returns its surrogate id. */
    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static PartnerCorridorCommand lane(String srcCountry, String srcCcy,
                                               String dstCountry, String dstCcy) {
        return new PartnerCorridorCommand(srcCountry, srcCcy, dstCountry, dstCcy, null, null);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void bulkReplace_insertsThenSupersedesWholeSet_scd6Paired() {
        Long partnerId = seedPartner("CORR_REPLACE");

        List<PartnerCorridorView> first = service.replaceDraftCorridors("CORR_REPLACE",
                List.of(
                        new PartnerCorridorCommand("KR", "KRW", "MN", "MNT",
                                LocalDate.of(2026, 7, 1), true),
                        lane("KR", "KRW", "VN", "VND")),
                "maker_kim");

        assertThat(first).hasSize(2);
        assertThat(first.get(0).partnerId()).isEqualTo(partnerId);
        assertThat(first.get(0).srcCountry()).isEqualTo("KR");
        assertThat(first.get(0).srcCcy()).isEqualTo("KRW");
        assertThat(first.get(0).dstCountry()).isEqualTo("MN");
        assertThat(first.get(0).dstCcy()).isEqualTo("MNT");
        assertThat(first.get(0).goLiveDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(first.get(0).isActive()).isTrue();
        // Omitted isActive defaults to the V023 column DEFAULT TRUE;
        // omitted goLiveDate stays null (not yet scheduled).
        assertThat(first.get(1).isActive()).isTrue();
        assertThat(first.get(1).goLiveDate()).isNull();

        List<PartnerCorridorView> second = service.replaceDraftCorridors("CORR_REPLACE",
                List.of(new PartnerCorridorCommand("KR", "KRW", "MN", "MNT", null, false)),
                "maker_kim");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).isActive()).isFalse();

        // SCD-6: nothing deleted — 3 rows total, the prior 2 superseded with an
        // instant EXACTLY equal to the fresh recorded_at (shared paired-write
        // instant), MICROS-truncated.
        List<PartnerCorridorEntity> all = corridorRepository.findAll().stream()
                .filter(e -> partnerId.equals(e.getPartnerId())).toList();
        assertThat(all).hasSize(3);
        List<PartnerCorridorEntity> superseded = all.stream()
                .filter(e -> e.getSupersededAt() != null).toList();
        PartnerCorridorEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(superseded).hasSize(2);
        assertThat(superseded).allSatisfy(p ->
                assertThat(p.getSupersededAt()).isEqualTo(current.getRecordedAt()));
        assertThat(current.getRecordedAt().getNano() % 1000).isZero();

        // Rehydrate path returns only the current set.
        assertThat(service.currentCorridors("CORR_REPLACE"))
                .extracting(PartnerCorridorView::dstCcy).containsExactly("MNT");

        // An empty list clears all corridors (replace semantics).
        assertThat(service.replaceDraftCorridors("CORR_REPLACE", List.of(), "maker_kim"))
                .isEmpty();
        assertThat(service.currentCorridors("CORR_REPLACE")).isEmpty();
        assertThat(corridorRepository.findAllCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void reinsertingTheSameLaneAcrossReplaces_doesNotTripTheUniqueIndex() {
        seedPartner("CORR_SAME_LANE");

        // Same lane in both saves: the supersede must flush before the insert
        // so the V023 partial-unique never sees two current rows mid-replace.
        service.replaceDraftCorridors("CORR_SAME_LANE",
                List.of(lane("KR", "KRW", "MN", "MNT")), "maker_kim");
        List<PartnerCorridorView> second = service.replaceDraftCorridors("CORR_SAME_LANE",
                List.of(new PartnerCorridorCommand("KR", "KRW", "MN", "MNT", null, false)),
                "maker_kim");

        assertThat(second).hasSize(1);
        assertThat(second.get(0).isActive()).isFalse();
    }

    @Test
    void validation_rejectsBadPayloadsWith400_withoutWritingRows() {
        Long partnerId = seedPartner("CORR_INVALID");

        record Bad(String label, List<PartnerCorridorCommand> corridors) {}
        List<Bad> bads = List.of(
                new Bad("missing srcCountry", List.of(lane(null, "KRW", "MN", "MNT"))),
                new Bad("blank srcCountry", List.of(lane("  ", "KRW", "MN", "MNT"))),
                new Bad("lowercase srcCountry", List.of(lane("kr", "KRW", "MN", "MNT"))),
                new Bad("3-letter srcCountry", List.of(lane("KOR", "KRW", "MN", "MNT"))),
                new Bad("missing srcCcy", List.of(lane("KR", null, "MN", "MNT"))),
                new Bad("2-letter srcCcy", List.of(lane("KR", "KW", "MN", "MNT"))),
                new Bad("lowercase srcCcy", List.of(lane("KR", "krw", "MN", "MNT"))),
                new Bad("missing dstCountry", List.of(lane("KR", "KRW", null, "MNT"))),
                new Bad("numeric dstCountry", List.of(lane("KR", "KRW", "M1", "MNT"))),
                new Bad("missing dstCcy", List.of(lane("KR", "KRW", "MN", null))),
                new Bad("4-letter dstCcy", List.of(lane("KR", "KRW", "MN", "MNTT"))),
                new Bad("duplicate lane", List.of(
                        lane("KR", "KRW", "MN", "MNT"),
                        new PartnerCorridorCommand("KR", "KRW", "MN", "MNT",
                                LocalDate.of(2026, 8, 1), false))));

        for (Bad bad : bads) {
            assertThatThrownBy(() -> service.replaceDraftCorridors(
                    "CORR_INVALID", bad.corridors(), "maker_kim"))
                    .as(bad.label())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        // A 400 must be side-effect free: no corridor row landed.
        assertThat(corridorRepository.findAllCurrentByPartnerId(partnerId)).isEmpty();
    }

    @Test
    void validationMessages_carryTheOffendingIndexAndField() {
        seedPartner("CORR_MSG");

        assertThatThrownBy(() -> service.replaceDraftCorridors("CORR_MSG", List.of(
                        lane("KR", "KRW", "MN", "MNT"),
                        lane("KR", "KRW", "MN", "mnt")),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("corridors[1]");
                    assertThat(e.getReason()).contains("dstCcy");
                });
    }

    @Test
    void nullList_isRejectedWith400() {
        seedPartner("CORR_NULL");
        assertThatThrownBy(() -> service.replaceDraftCorridors("CORR_NULL", null, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownPartner_404_onBothOperations() {
        assertThatThrownBy(() -> service.replaceDraftCorridors(
                "CORR_GHOST", List.of(), "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.currentCorridors("CORR_GHOST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void partnerWithoutCorridors_returnsEmptyListOnRead() {
        seedPartner("CORR_EMPTY");
        assertThat(service.currentCorridors("CORR_EMPTY")).isEmpty();
    }

    @Test
    void nonOnboardingPartner_409_postActivationFlowIsSlice8() {
        seedPartner("CORR_LIVE");
        service.replaceDraftCorridors("CORR_LIVE",
                List.of(lane("KR", "KRW", "MN", "MNT")), "maker_kim");

        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("CORR_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.replaceDraftCorridors("CORR_LIVE", List.of(),
                "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // Reads stay open for LIVE partners (SchemeRouter / corridor gate).
        assertThat(service.currentCorridors("CORR_LIVE")).hasSize(1);
    }

    @Test
    void audit_oneEventPerWrite_withCanonicalBeforeAfterSnapshots() {
        seedPartner("CORR_AUDIT");
        publisher.clear(); // drop the PARTNER_SAVED event from the seed

        service.replaceDraftCorridors("CORR_AUDIT", List.of(
                new PartnerCorridorCommand("KR", "KRW", "MN", "MNT",
                        LocalDate.of(2026, 7, 1), true)), "maker_kim");
        service.replaceDraftCorridors("CORR_AUDIT", List.of(
                new PartnerCorridorCommand("KR", "KRW", "VN", "VND", null, false)),
                "checker_lee");

        List<AuditEvent> events = publisher.published();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.aggregateType()).isEqualTo("partner_corridor");
        assertThat(first.aggregateId()).isEqualTo("CORR_AUDIT");
        assertThat(first.eventType()).isEqualTo("PARTNER_CORRIDORS_REPLACED");
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb()).as("first write — BEFORE must be null").isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"srcCountry\":\"KR\"")
                .contains("\"srcCcy\":\"KRW\"")
                .contains("\"dstCountry\":\"MN\"")
                .contains("\"dstCcy\":\"MNT\"")
                .contains("\"goLiveDate\":\"2026-07-01\"")
                .contains("\"isActive\":true");

        AuditEvent second = events.get(1);
        assertThat(second.actorId()).isEqualTo("checker_lee");
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .as("BEFORE carries the superseded set")
                .contains("\"dstCcy\":\"MNT\"");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("\"dstCcy\":\"VND\"")
                .contains("\"goLiveDate\":null")
                .contains("\"isActive\":false");
    }
}
