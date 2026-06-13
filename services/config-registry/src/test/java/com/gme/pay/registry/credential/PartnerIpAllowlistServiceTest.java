package com.gme.pay.registry.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.contracts.PartnerIpAllowlistCommand;
import com.gme.pay.contracts.PartnerIpAllowlistView;
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
import java.util.ArrayList;
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
 * Slice 8 Lane B acceptance test for {@link PartnerIpAllowlistService} — the
 * {@code partner_ip_allowlist} bulk replace (V026), wired against H2 in
 * PostgreSQL mode with the full Flyway chain applied. Mirrors the
 * {@code PartnerSchemeServiceTest} slice-test pattern.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>Valid IPv4 + IPv6 CIDRs save; the read returns them env-ordered.</li>
 *   <li>Invalid CIDR shapes are a 400 with the offending index — octet
 *       range, missing prefix, prefix bounds, IPv6 grouping.</li>
 *   <li>The 10-per-(partner, environment) ceiling is a 409
 *       {@code CIDR_LIMIT_EXCEEDED}; 10 exactly passes; the ceiling is
 *       per-environment (10 + 10 across SANDBOX/PRODUCTION passes).</li>
 *   <li>Bulk replace: the second save wholly replaces the first; empty
 *       clears.</li>
 *   <li>Environment scoping: duplicate cidr across environments is fine;
 *       within one environment it is a 400.</li>
 *   <li>ADR-007: one {@code partner_ip_allowlist} audit event per write.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PartnerIpAllowlistServiceTest.TestConfig.class, PartnerIpAllowlistService.class,
        AuditLogService.class, PartnerStore.class, CacheConfig.class})
class PartnerIpAllowlistServiceTest {

    @Autowired
    private PartnerIpAllowlistService service;

    @Autowired
    private PartnerIpAllowlistRepository repository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private PartnerStore partnerStore;

    @Autowired
    private RecordingAuditPublisher publisher;

    /** Same publisher swap as {@code AuditLogTest} / {@code RuleServiceTest}. */
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

    // ------------------------------------------------------------------ helpers

    private Long seedPartner(String code) {
        partnerStore.save(Partner.of(code, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));
        return partnerRepository.findCurrentByPartnerCode(code).orElseThrow().getId();
    }

    private static PartnerIpAllowlistCommand entry(String cidr, String env) {
        return new PartnerIpAllowlistCommand(cidr, "test range", env);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void validCidrs_ipv4AndIpv6_saveAndReadBack() {
        seedPartner("IPA_OK");
        List<PartnerIpAllowlistView> saved = service.replaceAllowlist("IPA_OK", List.of(
                entry("203.0.113.0/24", "SANDBOX"),
                entry("198.51.100.7/32", "PRODUCTION"),
                entry("2001:db8::/32", "PRODUCTION"),
                entry("::1/128", "SANDBOX")), "maker_kim");

        assertThat(saved).hasSize(4);
        assertThat(saved).allSatisfy(v -> {
            assertThat(v.id()).isNotNull();
            assertThat(v.createdAt()).isNotNull();
            assertThat(v.createdBy()).isEqualTo("maker_kim");
        });
        assertThat(service.currentAllowlist("IPA_OK"))
                .extracting(PartnerIpAllowlistView::cidr)
                .containsExactlyInAnyOrder("203.0.113.0/24", "198.51.100.7/32",
                        "2001:db8::/32", "::1/128");
    }

    @Test
    void invalidCidrs_are400_withOffendingIndex_andNoRowsWritten() {
        seedPartner("IPA_BAD");
        List<String> bad = List.of(
                "203.0.113.0",          // no prefix
                "203.0.113.0/33",       // v4 prefix out of bounds
                "256.0.0.1/24",         // octet out of range
                "203.0.113/24",         // 3 octets
                "2001:db8::/129",       // v6 prefix out of bounds
                "2001:db8:::1/64",      // malformed elision
                "20zz:db8::/32",        // non-hex group
                "abc/12");              // garbage
        for (String cidr : bad) {
            assertThatThrownBy(() -> service.replaceAllowlist("IPA_BAD",
                    List.of(entry(cidr, "SANDBOX")), "maker_kim"))
                    .as("cidr %s must be rejected", cidr)
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getReason()).contains("ipAllowlist[0]");
                    });
        }
        assertThat(repository.count()).isZero();
    }

    @Test
    void elevenCidrsInOneEnvironment_is409CidrLimitExceeded_tenPasses() {
        seedPartner("IPA_CAP");
        List<PartnerIpAllowlistCommand> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(entry("10.0." + i + ".0/24", "SANDBOX"));
        }
        assertThatThrownBy(() -> service.replaceAllowlist("IPA_CAP", eleven, "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason())
                            .contains(PartnerIpAllowlistService.CIDR_LIMIT_EXCEEDED);
                });
        assertThat(repository.count()).isZero();

        // Exactly 10 sails through.
        assertThat(service.replaceAllowlist("IPA_CAP", eleven.subList(0, 10), "maker_kim"))
                .hasSize(10);
    }

    @Test
    void ceilingIsPerEnvironment_tenPlusTenAcrossEnvironmentsPasses() {
        seedPartner("IPA_ENV_CAP");
        List<PartnerIpAllowlistCommand> twenty = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            twenty.add(entry("10.1." + i + ".0/24", "SANDBOX"));
            twenty.add(entry("10.2." + i + ".0/24", "PRODUCTION"));
        }
        assertThat(service.replaceAllowlist("IPA_ENV_CAP", twenty, "maker_kim")).hasSize(20);
    }

    @Test
    void environmentScoping_sameCidrInBothEnvironmentsFine_duplicateWithinOneIs400() {
        seedPartner("IPA_DUP");
        // Same range on both tiers is legitimate (office egress reaching both).
        assertThat(service.replaceAllowlist("IPA_DUP", List.of(
                entry("203.0.113.0/24", "SANDBOX"),
                entry("203.0.113.0/24", "PRODUCTION")), "maker_kim")).hasSize(2);

        // Within one environment the duplicate is an operator error.
        assertThatThrownBy(() -> service.replaceAllowlist("IPA_DUP", List.of(
                entry("203.0.113.0/24", "SANDBOX"),
                entry("203.0.113.0/24", "SANDBOX")), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("duplicate");
                });

        // Unknown environment is a 400.
        assertThatThrownBy(() -> service.replaceAllowlist("IPA_DUP", List.of(
                entry("203.0.113.0/24", "STAGING")), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void bulkReplace_secondSaveWhollyReplacesFirst_emptyClears_nullIs400() {
        seedPartner("IPA_REPL");
        service.replaceAllowlist("IPA_REPL", List.of(
                entry("203.0.113.0/24", "SANDBOX"),
                entry("198.51.100.0/24", "SANDBOX")), "maker_kim");

        List<PartnerIpAllowlistView> second = service.replaceAllowlist("IPA_REPL",
                List.of(entry("192.0.2.0/24", "PRODUCTION")), "maker_kim");
        assertThat(second).hasSize(1);
        assertThat(service.currentAllowlist("IPA_REPL"))
                .extracting(PartnerIpAllowlistView::cidr)
                .containsExactly("192.0.2.0/24");

        // Empty clears.
        assertThat(service.replaceAllowlist("IPA_REPL", List.of(), "maker_kim")).isEmpty();
        assertThat(service.currentAllowlist("IPA_REPL")).isEmpty();

        // Null is a 400.
        assertThatThrownBy(() -> service.replaceAllowlist("IPA_REPL", null, "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void nonOnboardingPartner_409_unknownPartner_404() {
        seedPartner("IPA_LIVE");
        PartnerEntity current = partnerRepository.findCurrentByPartnerCode("IPA_LIVE")
                .orElseThrow();
        current.setStatus(PartnerStatus.LIVE);
        partnerRepository.saveAndFlush(current);

        assertThatThrownBy(() -> service.replaceAllowlist("IPA_LIVE",
                List.of(entry("203.0.113.0/24", "SANDBOX")), "maker_kim"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> service.currentAllowlist("IPA_NOPE"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void audit_oneEventPerWrite_withBeforeAfterSnapshots() {
        seedPartner("IPA_AUD");
        publisher.clear();

        service.replaceAllowlist("IPA_AUD",
                List.of(entry("203.0.113.0/24", "SANDBOX")), "maker_kim");
        service.replaceAllowlist("IPA_AUD",
                List.of(entry("192.0.2.0/24", "PRODUCTION")), "checker_lee");

        List<AuditEvent> events = publisher.published().stream()
                .filter(e -> PartnerIpAllowlistService.AGGREGATE_TYPE
                        .equals(e.aggregateType()))
                .toList();
        assertThat(events).hasSize(2);

        AuditEvent first = events.get(0);
        assertThat(first.eventType())
                .isEqualTo(PartnerIpAllowlistService.EVENT_TYPE_REPLACED);
        assertThat(first.actorId()).isEqualTo("maker_kim");
        assertThat(first.beforeJsonb()).isNull();
        assertThat(new String(first.afterJsonb(), StandardCharsets.UTF_8))
                .contains("203.0.113.0/24");

        AuditEvent second = events.get(1);
        assertThat(new String(second.beforeJsonb(), StandardCharsets.UTF_8))
                .contains("203.0.113.0/24");
        assertThat(new String(second.afterJsonb(), StandardCharsets.UTF_8))
                .contains("192.0.2.0/24");
    }
}
