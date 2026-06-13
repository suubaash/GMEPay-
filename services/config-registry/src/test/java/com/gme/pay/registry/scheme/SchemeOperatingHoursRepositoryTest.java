package com.gme.pay.registry.scheme;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * Slice 7 — pins the V024 {@code scheme_operating_hours} seed (applied by
 * Flyway on H2 in PostgreSQL mode) and the read path through
 * {@link SchemeOperatingHoursRepository}. The table is read-only reference
 * data: no service writes it, so the whole contract is "the migration seeded
 * exactly what the router/settlement calculator expect".
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemeOperatingHoursRepositoryTest {

    @Autowired
    private SchemeOperatingHoursRepository repository;

    @Test
    void seed_fiveSchemesTimesSevenWeekdays_arePresentAfterMigration() {
        // ZEROPAY, BAKONG, NAPAS_247, PROMPT_PAY, FAST_SG × Monday..Sunday.
        // QRIS / KHQR are rostered in V022 but deliberately not seeded yet.
        assertThat(repository.count()).isEqualTo(35);
        assertThat(repository.findBySchemeIdOrderByWeekday("QRIS")).isEmpty();
        assertThat(repository.findBySchemeIdOrderByWeekday("KHQR")).isEmpty();
    }

    @Test
    void zeropay_sevenRows_24x7_withKftcCutoff1630Seoul() {
        var rows = repository.findBySchemeIdOrderByWeekday("ZEROPAY");

        assertThat(rows).hasSize(7);
        assertThat(rows)
                .extracting(SchemeOperatingHoursEntity::getWeekday)
                .containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getOpenTimeLocal()).isEqualTo(LocalTime.MIDNIGHT);
            assertThat(r.getCloseTimeLocal()).isEqualTo(LocalTime.of(23, 59, 59));
            assertThat(r.getCutoffTimeLocal())
                    .as("KFTC interbank settlement cutoff 16:30 KST")
                    .isEqualTo(LocalTime.of(16, 30));
            assertThat(r.getTimezone()).isEqualTo("Asia/Seoul");
        });
    }

    @Test
    void bakong_sevenRows_24x7_withNbcWindowCloseAsCutoff_ict() {
        var rows = repository.findBySchemeIdOrderByWeekday("BAKONG");

        assertThat(rows).hasSize(7);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getOpenTimeLocal()).isEqualTo(LocalTime.MIDNIGHT);
            assertThat(r.getCloseTimeLocal()).isEqualTo(LocalTime.of(23, 59, 59));
            assertThat(r.getCutoffTimeLocal())
                    .as("NBC settlement window 09:00-15:00 ICT — 15:00 is the daily cutoff")
                    .isEqualTo(LocalTime.of(15, 0));
            assertThat(r.getTimezone()).isEqualTo("Asia/Phnom_Penh");
        });
    }

    @Test
    void instantRails_napas247PromptPayFastSg_have24x7AndNoCutoff() {
        record Expected(String schemeId, String timezone) {}
        var expectations = java.util.List.of(
                new Expected("NAPAS_247", "Asia/Ho_Chi_Minh"),
                new Expected("PROMPT_PAY", "Asia/Bangkok"),
                new Expected("FAST_SG", "Asia/Singapore"));

        for (Expected expected : expectations) {
            var rows = repository.findBySchemeIdOrderByWeekday(expected.schemeId());
            assertThat(rows).as(expected.schemeId()).hasSize(7);
            assertThat(rows).allSatisfy(r -> {
                assertThat(r.getOpenTimeLocal()).isEqualTo(LocalTime.MIDNIGHT);
                assertThat(r.getCloseTimeLocal()).isEqualTo(LocalTime.of(23, 59, 59));
                assertThat(r.getCutoffTimeLocal())
                        .as(expected.schemeId() + " is a 24x7 instant rail — no cutoff")
                        .isNull();
                assertThat(r.getTimezone()).isEqualTo(expected.timezone());
            });
        }
    }

    @Test
    void findBySchemeId_returnsWeekdaysInOrder_mondayThroughSunday() {
        var rows = repository.findBySchemeIdOrderByWeekday("FAST_SG");
        assertThat(rows)
                .extracting(SchemeOperatingHoursEntity::getWeekday)
                .isSorted()
                .containsExactly(0, 1, 2, 3, 4, 5, 6);
    }

    @Test
    void viewAdapter_carriesAllSixFields() {
        var view = repository.findBySchemeIdOrderByWeekday("ZEROPAY").get(0).toView();
        assertThat(view.schemeId()).isEqualTo("ZEROPAY");
        assertThat(view.weekday()).isZero();
        assertThat(view.openTimeLocal()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(view.closeTimeLocal()).isEqualTo(LocalTime.of(23, 59, 59));
        assertThat(view.cutoffTimeLocal()).isEqualTo(LocalTime.of(16, 30));
        assertThat(view.timezone()).isEqualTo("Asia/Seoul");
    }
}
