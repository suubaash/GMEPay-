package com.gme.pay.registry.scheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.MerchantFeeScheduleCommand;
import com.gme.pay.contracts.MerchantFeeScheduleView;
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
 * Slice test for {@link MerchantFeeScheduleService} — the configurable gross
 * merchant fee schedule (V032) + the exact-type-over-default resolveRate used
 * by the payment-path snapshot.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MerchantFeeScheduleServiceTest.TestConfig.class, MerchantFeeScheduleService.class,
        SchemeCatalogService.class, AuditLogService.class})
class MerchantFeeScheduleServiceTest {

    @Autowired
    private MerchantFeeScheduleService service;

    @Autowired
    private MerchantFeeScheduleRepository repository;

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

    private static MerchantFeeScheduleCommand fee(String type, String pct) {
        return new MerchantFeeScheduleCommand(type, pct == null ? null : new BigDecimal(pct));
    }

    @Test
    void replace_normalizesScale_andSecondReplaceSupersedes() {
        List<MerchantFeeScheduleView> first = service.replaceMerchantFees("ZEROPAY",
                List.of(fee("RETAIL", "0.0080"), fee("FOOD_BEVERAGE", "0.0220"), fee(null, "0.0150")),
                "maker_kim");
        assertThat(first).hasSize(3);
        assertThat(first.get(0).merchantFeePct().scale()).isEqualTo(4);

        // replace with one row supersedes the prior three
        List<MerchantFeeScheduleView> second = service.replaceMerchantFees("ZEROPAY",
                List.of(fee("RETAIL", "0.0090")), "maker_kim");
        assertThat(second).hasSize(1);

        List<MerchantFeeScheduleEntity> all = repository.findAll().stream()
                .filter(e -> "ZEROPAY".equals(e.getSchemeId())).toList();
        assertThat(all).hasSize(4);
        assertThat(all.stream().filter(e -> e.getSupersededAt() != null).toList()).hasSize(3);
        assertThat(service.currentMerchantFees("ZEROPAY")).hasSize(1);
    }

    @Test
    void resolveRate_exactTypeBeatsDefault() {
        service.replaceMerchantFees("ZEROPAY",
                List.of(fee("RETAIL", "0.0080"), fee(null, "0.0150")), "a");

        // exact merchant type
        assertThat(service.resolveRate("ZEROPAY", "RETAIL")).contains(new BigDecimal("0.0080"));
        // unknown type → scheme default (merchant_type = NULL)
        assertThat(service.resolveRate("ZEROPAY", "PHARMACY")).contains(new BigDecimal("0.0150"));
        // null type → default
        assertThat(service.resolveRate("ZEROPAY", null)).contains(new BigDecimal("0.0150"));
    }

    @Test
    void resolveRate_emptyWhenNoApplicableRow() {
        service.replaceMerchantFees("ZEROPAY", List.of(fee("RETAIL", "0.0080")), "a");
        // no default row and the queried type doesn't match → empty
        assertThat(service.resolveRate("ZEROPAY", "FOOD_BEVERAGE")).isEmpty();
        // unknown scheme → empty (lenient, no exception)
        assertThat(service.resolveRate("NOPE", "RETAIL")).isEmpty();
    }

    @Test
    void validation_rejectsBadInputs() {
        assertThatThrownBy(() -> service.replaceMerchantFees("ZEROPAY", null, "a"))
                .isInstanceOf(ResponseStatusException.class);
        assertBadRequest(fee("RETAIL", null));        // null pct
        assertBadRequest(fee("RETAIL", "1.5"));       // > 1
        assertBadRequest(fee("RETAIL", "-0.01"));     // negative
        assertBadRequest(fee("RETAIL", "0.00805"));   // > 4 dp

        // duplicate merchant type
        assertThatThrownBy(() -> service.replaceMerchantFees("ZEROPAY",
                List.of(fee("RETAIL", "0.0080"), fee("RETAIL", "0.0090")), "a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(service.currentMerchantFees("ZEROPAY")).isEmpty();
    }

    @Test
    void unknownScheme_is404() {
        assertThatThrownBy(() -> service.replaceMerchantFees("NOTASCHEME",
                List.of(fee("RETAIL", "0.0080")), "a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void assertBadRequest(MerchantFeeScheduleCommand cmd) {
        assertThatThrownBy(() -> service.replaceMerchantFees("ZEROPAY", List.of(cmd), "a"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
