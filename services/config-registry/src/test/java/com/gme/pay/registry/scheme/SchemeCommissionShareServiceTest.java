package com.gme.pay.registry.scheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.SchemeCommissionShareCommand;
import com.gme.pay.contracts.SchemeCommissionShareView;
import com.gme.pay.registry.audit.AuditLogService;
import java.math.BigDecimal;
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
 * Slice test for {@link SchemeCommissionShareService} — the configurable
 * GME ↔ scheme commission split ({@code scheme_commission_share}, V031),
 * wired against H2 in PostgreSQL mode with the full Flyway chain. Mirrors
 * {@code FeeScheduleServiceTest}'s {@code @DataJpaTest} slice pattern.
 *
 * <p>Pins: bulk-replace SCD-6, empty-list clears, scale-4 normalization, the
 * validation envelope (direction roster, gmeShare in (0,1], van &ge; 0, &le; 4
 * dp, duplicate direction), and unknown-scheme 404.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemeCommissionShareServiceTest.TestConfig.class, SchemeCommissionShareService.class,
        SchemeCatalogService.class, AuditLogService.class})
class SchemeCommissionShareServiceTest {

    @Autowired
    private SchemeCommissionShareService service;

    @Autowired
    private SchemeCommissionShareRepository repository;

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

    private static SchemeCommissionShareCommand share(String dir, String gme, String van) {
        return new SchemeCommissionShareCommand(dir,
                gme == null ? null : new BigDecimal(gme),
                van == null ? null : new BigDecimal(van));
    }

    @Test
    void replace_normalizesScale_vanDefaultsToZero_andSecondReplaceSupersedes() {
        List<SchemeCommissionShareView> first = service.replaceCommissionShares("ZEROPAY",
                List.of(share("BOTH", "0.70", "0.0008"),
                        share(null, "0.65", null)),
                "maker_kim");

        assertThat(first).hasSize(2);
        assertThat(first.get(0).schemeId()).isEqualTo("ZEROPAY");
        assertThat(first.get(0).direction()).isEqualTo("BOTH");
        assertThat(first.get(0).gmeSharePct()).isEqualByComparingTo(new BigDecimal("0.70"));
        assertThat(first.get(0).gmeSharePct().scale()).isEqualTo(4);
        assertThat(first.get(0).vanFeePct()).isEqualByComparingTo(new BigDecimal("0.0008"));
        assertThat(first.get(1).direction()).as("wildcard default row").isNull();
        assertThat(first.get(1).vanFeePct()).as("van defaults to 0").isEqualByComparingTo(BigDecimal.ZERO);

        List<SchemeCommissionShareView> second = service.replaceCommissionShares("ZEROPAY",
                List.of(share("BOTH", "0.75", null)), "maker_kim");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).gmeSharePct()).isEqualByComparingTo(new BigDecimal("0.75"));

        // SCD-6: nothing deleted; 3 rows total, 2 superseded.
        List<SchemeCommissionShareEntity> all = repository.findAll().stream()
                .filter(e -> "ZEROPAY".equals(e.getSchemeId())).toList();
        assertThat(all).hasSize(3);
        SchemeCommissionShareEntity current = all.stream()
                .filter(e -> e.getSupersededAt() == null).findFirst().orElseThrow();
        assertThat(all.stream().filter(e -> e.getSupersededAt() != null).toList())
                .hasSize(2)
                .allSatisfy(p -> assertThat(p.getSupersededAt()).isEqualTo(current.getRecordedAt()));
        assertThat(service.currentCommissionShares("ZEROPAY")).hasSize(1);
    }

    @Test
    void caseInsensitiveSchemeMatch_andEmptyListClears() {
        service.replaceCommissionShares("zeropay", List.of(share("BOTH", "0.7", null)), "a");
        assertThat(service.currentCommissionShares("ZEROPAY")).hasSize(1);
        service.replaceCommissionShares("ZEROPAY", List.of(), "a");
        assertThat(service.currentCommissionShares("zeropay")).isEmpty();
    }

    @Test
    void validation_rejectsBadInputs() {
        // null payload
        assertThatThrownBy(() -> service.replaceCommissionShares("ZEROPAY", null, "a"))
                .isInstanceOf(ResponseStatusException.class);
        // bad direction
        assertBadRequest("ZEROPAY", share("SIDEWAYS", "0.7", null));
        // gmeShare must be strictly > 0
        assertBadRequest("ZEROPAY", share("BOTH", "0", null));
        // gmeShare > 1
        assertBadRequest("ZEROPAY", share("BOTH", "1.2", null));
        // null gmeShare
        assertBadRequest("ZEROPAY", share("BOTH", null, null));
        // > 4 dp
        assertBadRequest("ZEROPAY", share("BOTH", "0.70001", null));
        // negative van
        assertBadRequest("ZEROPAY", share("BOTH", "0.7", "-0.01"));
        // duplicate direction
        assertThatThrownBy(() -> service.replaceCommissionShares("ZEROPAY",
                List.of(share("BOTH", "0.7", null), share("BOTH", "0.6", null)), "a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(service.currentCommissionShares("ZEROPAY")).isEmpty();
    }

    @Test
    void vanOverNumericEnvelope_is400_not500() {
        // Regression: van >= 1000 fits no NUMERIC(7,4) column — must be a clean 400,
        // not a DataIntegrityViolation 500 on write.
        assertBadRequest("ZEROPAY", share("BOTH", "0.7", "1000"));
        // boundary: 999.9999 is the max and must be accepted
        List<SchemeCommissionShareView> ok = service.replaceCommissionShares("ZEROPAY",
                List.of(share("BOTH", "0.7", "999.9999")), "a");
        assertThat(ok.get(0).vanFeePct()).isEqualByComparingTo(new BigDecimal("999.9999"));
    }

    @Test
    void trailingZeroShare_isAccepted_likePartnerSide() {
        // "0.70000" is 70% padded — must be accepted (scale check strips trailing zeros).
        List<SchemeCommissionShareView> v = service.replaceCommissionShares("ZEROPAY",
                List.of(share("BOTH", "0.70000", "0.00080")), "a");
        assertThat(v.get(0).gmeSharePct()).isEqualByComparingTo(new BigDecimal("0.70"));
        assertThat(v.get(0).vanFeePct()).isEqualByComparingTo(new BigDecimal("0.0008"));
    }

    @Test
    void gmeShareOfOne_isAccepted() {
        List<SchemeCommissionShareView> v = service.replaceCommissionShares("ZEROPAY",
                List.of(share("BOTH", "1", null)), "a");
        assertThat(v.get(0).gmeSharePct()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void unknownScheme_is404() {
        assertThatThrownBy(() -> service.replaceCommissionShares("NOTASCHEME",
                List.of(share("BOTH", "0.7", null)), "a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void assertBadRequest(String scheme, SchemeCommissionShareCommand cmd) {
        assertThatThrownBy(() -> service.replaceCommissionShares(scheme, List.of(cmd), "a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
